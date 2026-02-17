package eval.runner

import eval.context.ContextBuilder
import eval.context.PromptAssembler
import eval.model.*
import eval.provider.GeneratedCode
import eval.provider.ModelProvider
import eval.scoring.Scorer
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant

/**
 * Executes a single task against a single model with retry loop.
 *
 * Flow per attempt:
 * 1. Build/reuse project context
 * 2. Assemble prompt (first attempt or retry with errors)
 * 3. Call model provider
 * 4. Reset sandbox to main, apply generated code
 * 5. Run compile; if fail and attempts remain, retry
 * 6. If compile succeeds, run tests
 * 7. Collect attempt data and produce EvalResult
 */
class TaskExecutor(
    private val sandbox: ProjectSandbox,
    private val gradleExecutor: GradleExecutor,
    private val contextBuilder: ContextBuilder,
) {
    private val logger = LoggerFactory.getLogger(TaskExecutor::class.java)
    suspend fun execute(
        task: TaskDefinition,
        provider: ModelProvider,
    ): EvalResult {
        val startTime = System.currentTimeMillis()
        val taskDescription = sandbox.readTaskDescription(task.branch)
        val context = contextBuilder.build(task.contextFiles)
        val attempts = mutableListOf<Attempt>()
        var previousCode: Map<String, String>? = null
        var previousErrors: List<String>? = null

        for (attemptNum in 1..task.maxAttempts) {
            val attemptStart = System.currentTimeMillis()

            // Build the prompt
            val prompt = if (attemptNum == 1 || previousErrors == null) {
                PromptAssembler.assembleFirstAttempt(taskDescription, context)
            } else {
                PromptAssembler.assembleRetryAttempt(
                    taskDescription,
                    previousErrors!!,
                    previousCode ?: emptyMap(),
                    context,
                )
            }

            // Call the model
            val generated: GeneratedCode = provider.generateCode(
                prompt = prompt,
                projectContext = context,
                previousErrors = previousErrors,
            )

            // Reset sandbox and apply code
            sandbox.resetToMain()
            sandbox.applyGeneratedCode(generated.files)

            // Compile
            val compileResult = gradleExecutor.runCompile(task.verification.compileTask)
            val compilerErrors = if (!compileResult.success) {
                GradleExecutor.parseCompilerErrorMessages(compileResult.stdout + "\n" + compileResult.stderr)
            } else {
                emptyList()
            }

            // Test (only if compile succeeded)
            var testSuccess = false
            var testResults: TestResults? = null
            var testGradleOutput = ""
            if (compileResult.success) {
                val testResult = gradleExecutor.runTests(task.verification.testTask)
                testSuccess = testResult.success
                testGradleOutput = testResult.stdout + "\n" + testResult.stderr

                // Try JUnit XML first, fall back to parsing Gradle console output
                val testTaskName = task.verification.testTask.substringAfterLast(":")
                val testResultsDir = findTestResultsDir(sandbox.workDir, testTaskName)
                testResults = if (testResultsDir != null) {
                    GradleExecutor.parseTestResultsFromXml(testResultsDir)
                } else {
                    null
                }
                if (testResults == null) {
                    testResults = GradleExecutor.parseTestResultsFromOutput(testGradleOutput)
                }
            }

            val attemptDuration = System.currentTimeMillis() - attemptStart
            val attempt = Attempt(
                attemptNumber = attemptNum,
                generatedCode = generated.files,
                compileSuccess = compileResult.success,
                compilerErrors = compilerErrors,
                testSuccess = testSuccess,
                testResults = testResults,
                durationMs = attemptDuration,
            )
            attempts.add(attempt)

            // If tests passed, we're done
            if (testSuccess) break

            // For PARTIAL-expected tasks, compiling is enough — don't retry
            if (task.expectedOutcome == Outcome.PARTIAL && compileResult.success) {
                logger.info("Task expects PARTIAL outcome and code compiles — stopping retries")
                break
            }

            // If compile failed, prepare for retry
            if (!compileResult.success) {
                previousCode = generated.files
                previousErrors = compilerErrors.ifEmpty {
                    listOf(compileResult.stdout.takeLast(2000))
                }
            } else {
                // Compiled but tests failed — pass detailed feedback
                previousCode = generated.files
                previousErrors = buildTestErrorFeedback(testResults, testGradleOutput)
            }
        }

        val totalDuration = System.currentTimeMillis() - startTime
        val metrics = Scorer.computeMetrics(attempts, totalDuration)
        val finalOutcome = Scorer.determineFinalOutcome(attempts, task.expectedOutcome)

        // Compute LOC delta against reference solution on the task branch
        val codeDelta = computeCodeDelta(task.branch, attempts.lastOrNull()?.generatedCode)

        return EvalResult(
            taskId = task.id,
            model = provider.name,
            timestamp = Instant.now().toString(),
            attempts = attempts,
            finalOutcome = finalOutcome,
            metrics = metrics,
            codeDelta = codeDelta,
        )
    }

    private fun findTestResultsDir(projectDir: File, testTaskName: String): File? {
        // Walk through build directories looking for test-results
        val candidates = projectDir.walkTopDown()
            .filter { it.isDirectory && it.name == testTaskName && it.parentFile.name == "test-results" }
            .toList()
        return candidates.firstOrNull()
    }

    private fun computeCodeDelta(branch: String, generatedCode: Map<String, String>?): CodeDelta? {
        if (generatedCode.isNullOrEmpty()) return null

        val generatedLoc = generatedCode.values.sumOf { countNonBlankLines(it) }

        val referenceLoc = generatedCode.keys.sumOf { path ->
            val refContent = sandbox.readBranchFile(branch, path)
            if (refContent != null) countNonBlankLines(refContent) else 0
        }

        if (referenceLoc == 0) {
            logger.debug("No reference files found on branch '$branch' for LOC delta")
            return null
        }

        return CodeDelta(
            generatedLoc = generatedLoc,
            referenceLoc = referenceLoc,
            delta = generatedLoc - referenceLoc,
        )
    }

    private fun countNonBlankLines(content: String): Int =
        content.lines().count { it.isNotBlank() }

    private fun buildTestErrorFeedback(testResults: TestResults?, gradleOutput: String): List<String> {
        // First try structured test results
        if (testResults != null && testResults.failures.isNotEmpty()) {
            return testResults.failures.map { failure ->
                "${failure.className}.${failure.testName}: ${failure.message ?: "FAILED"}"
            }
        }

        // Fall back to the raw Gradle output (trimmed to last 3000 chars)
        val trimmedOutput = gradleOutput.takeLast(3000)
        if (trimmedOutput.isNotBlank()) {
            return listOf("Test output:\n$trimmedOutput")
        }

        return listOf("Tests failed but no detailed output was captured.")
    }

}
