package eval.runner

import eval.context.ContextBuilder
import eval.model.*
import eval.provider.ModelProvider
import eval.reporting.ComparisonReport
import eval.reporting.JsonReporter
import eval.reporting.MarkdownReporter
import eval.scoring.FailureCategorizer
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.util.UUID

class EvalRunner(
    private val repoPath: File,
    private val providers: List<ModelProvider>,
    private val taskFilter: Set<String>? = null,
    private val maxAttemptsOverride: Int? = null,
    private val parallel: Boolean = false,
    private val dryRun: Boolean = false,
    private val outputDir: File? = null,
) {
    private val logger = LoggerFactory.getLogger(EvalRunner::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun run(): EvalRun {
        val manifestFile = File(repoPath, "tasks.json")
        require(manifestFile.exists()) { "tasks.json not found at ${manifestFile.absolutePath}" }

        val manifest = json.decodeFromString<TaskManifest>(manifestFile.readText())
        logger.info("Loaded manifest for project: ${manifest.projectName}")

        val tasks = manifest.tasks
            .filter { taskFilter == null || it.id in taskFilter }
            .map { task ->
                if (maxAttemptsOverride != null) task.copy(maxAttempts = maxAttemptsOverride) else task
            }

        logger.info("Running ${tasks.size} task(s) against ${providers.size} model(s)")

        if (dryRun) {
            logger.info("Dry run — validating setup only")
            validate(manifest, tasks)
            return EvalRun(
                id = UUID.randomUUID().toString(),
                timestamp = Instant.now().toString(),
                projectName = manifest.projectName,
                repository = manifest.repository,
                models = providers.map { it.name },
                results = emptyList(),
            )
        }

        validate(manifest, tasks)

        val rawResults = if (parallel) {
            runParallel(manifest, tasks)
        } else {
            runSequential(manifest, tasks)
        }

        // Apply failure categorization to non-success results
        val results = rawResults.map { result ->
            if (result.finalOutcome != Outcome.SUCCESS && result.failureCategory == null) {
                result.copy(failureCategory = FailureCategorizer.categorize(result))
            } else {
                result
            }
        }

        val evalRun = EvalRun(
            id = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            projectName = manifest.projectName,
            repository = manifest.repository,
            models = providers.map { it.name },
            results = results,
        )

        // Write reports if output directory is configured
        if (outputDir != null) {
            logger.info("Writing reports to ${outputDir.absolutePath}")
            JsonReporter.write(evalRun, outputDir)
            MarkdownReporter.write(evalRun, outputDir)
            if (providers.size >= 2) {
                ComparisonReport.write(evalRun, outputDir)
            }
        }

        return evalRun
    }

    private fun validate(manifest: TaskManifest, tasks: List<TaskDefinition>) {
        logger.info("Validating task branches and TASK.md files...")
        val sandbox = ProjectSandbox.fromLocal(repoPath, manifest.mainBranch)
        try {
            for (task in tasks) {
                val taskMd = try {
                    sandbox.readTaskDescription(task.branch)
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Cannot read TASK.md from branch '${task.branch}' for task '${task.id}': ${e.message}"
                    )
                }
                require(taskMd.isNotBlank()) {
                    "TASK.md is empty on branch '${task.branch}' for task '${task.id}'"
                }
                logger.info("  [OK] ${task.id} — branch '${task.branch}' has TASK.md")
            }
        } finally {
            sandbox.cleanup()
        }
    }

    private suspend fun runSequential(
        manifest: TaskManifest,
        tasks: List<TaskDefinition>,
    ): List<EvalResult> {
        val results = mutableListOf<EvalResult>()
        for (provider in providers) {
            logger.info("Running model: ${provider.name}")
            val sandbox = ProjectSandbox.fromLocal(repoPath, manifest.mainBranch)
            try {
                val gradleExecutor = GradleExecutor(sandbox.workDir)
                val contextBuilder = ContextBuilder(sandbox.workDir)
                val executor = TaskExecutor(sandbox, gradleExecutor, contextBuilder)

                for (task in tasks) {
                    logger.info("  Task: ${task.id} (${task.difficulty})")
                    val result = executor.execute(task, provider)
                    results.add(result)
                    logger.info("  Result: ${result.finalOutcome} in ${result.attempts.size} attempt(s)")
                    sandbox.resetToMain()
                }
            } finally {
                sandbox.cleanup()
            }
        }
        return results
    }

    private suspend fun runParallel(
        manifest: TaskManifest,
        tasks: List<TaskDefinition>,
    ): List<EvalResult> = coroutineScope {
        val deferreds = providers.map { provider ->
            async(Dispatchers.IO) {
                logger.info("Running model (parallel): ${provider.name}")
                val sandbox = ProjectSandbox.fromLocal(repoPath, manifest.mainBranch)
                try {
                    val gradleExecutor = GradleExecutor(sandbox.workDir)
                    val contextBuilder = ContextBuilder(sandbox.workDir)
                    val executor = TaskExecutor(sandbox, gradleExecutor, contextBuilder)
                    val modelResults = mutableListOf<EvalResult>()

                    for (task in tasks) {
                        logger.info("  [${provider.name}] Task: ${task.id}")
                        val result = executor.execute(task, provider)
                        modelResults.add(result)
                        logger.info("  [${provider.name}] ${task.id}: ${result.finalOutcome}")
                        sandbox.resetToMain()
                    }
                    modelResults
                } finally {
                    sandbox.cleanup()
                }
            }
        }
        deferreds.flatMap { it.await() }
    }
}
