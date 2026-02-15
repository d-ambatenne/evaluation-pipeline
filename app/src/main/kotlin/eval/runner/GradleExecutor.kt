package eval.runner

import eval.model.TestFailure
import eval.model.TestResults
import java.io.File
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class GradleResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean,
)

data class CompilerError(
    val file: String,
    val line: Int,
    val column: Int,
    val message: String,
)

class GradleExecutor(
    private val projectDir: File,
    private val timeoutSeconds: Long = 300,
) {
    private val gradlew: String = if (System.getProperty("os.name").lowercase().contains("win")) {
        "gradlew.bat"
    } else {
        "./gradlew"
    }

    fun runCompile(compileTask: String): GradleResult = runGradle(compileTask)

    fun runTests(testTask: String): GradleResult = runGradle(testTask)

    private fun runGradle(task: String): GradleResult {
        val process = ProcessBuilder(gradlew, task, "--no-daemon", "--stacktrace")
            .directory(projectDir)
            .redirectErrorStream(false)
            .start()

        // Read stdout and stderr in separate threads to avoid deadlock
        // when pipe buffers fill up before the process exits.
        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()
        val stdoutThread = Thread {
            stdoutBuilder.append(process.inputStream.bufferedReader().readText())
        }
        val stderrThread = Thread {
            stderrBuilder.append(process.errorStream.bufferedReader().readText())
        }
        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(5000)
            stderrThread.join(5000)
            return GradleResult(
                exitCode = -1,
                stdout = stdoutBuilder.toString(),
                stderr = "Gradle task timed out after ${timeoutSeconds}s",
                success = false,
            )
        }

        stdoutThread.join(10000)
        stderrThread.join(10000)

        return GradleResult(
            exitCode = process.exitValue(),
            stdout = stdoutBuilder.toString(),
            stderr = stderrBuilder.toString(),
            success = process.exitValue() == 0,
        )
    }

    companion object {
        private val COMPILER_ERROR_PATTERN =
            Regex("""e:\s*(?:file://)?(.+?):(\d+):(\d+)\s+(.+)""")

        fun parseCompilerErrors(output: String): List<CompilerError> {
            return COMPILER_ERROR_PATTERN.findAll(output).map { match ->
                CompilerError(
                    file = match.groupValues[1],
                    line = match.groupValues[2].toInt(),
                    column = match.groupValues[3].toInt(),
                    message = match.groupValues[4].trim(),
                )
            }.toList()
        }

        fun parseCompilerErrorMessages(output: String): List<String> {
            return parseCompilerErrors(output).map { error ->
                "${error.file}:${error.line}:${error.column} ${error.message}"
            }
        }

        private val TEST_RESULT_LINE = Regex("""(\S+)\s+>\s+(.+?)\(\)\s+(PASSED|FAILED|SKIPPED)""")
        private val TEST_FAILURE_DETAIL = Regex("""(?:^\s+\S+.*(?:Error|Exception|assert).*$)""", RegexOption.MULTILINE)

        /**
         * Parse test results from Gradle's console output as a fallback
         * when JUnit XML reports are not available.
         */
        fun parseTestResultsFromOutput(output: String): TestResults? {
            val matches = TEST_RESULT_LINE.findAll(output).toList()
            if (matches.isEmpty()) return null

            var passed = 0
            var failed = 0
            var skipped = 0
            val failures = mutableListOf<TestFailure>()

            for (match in matches) {
                val className = match.groupValues[1]
                val testName = match.groupValues[2].trim()
                val status = match.groupValues[3]

                when (status) {
                    "PASSED" -> passed++
                    "FAILED" -> {
                        failed++
                        // Try to extract failure message from lines following the FAILED line
                        val afterMatch = output.substring(match.range.last + 1)
                        val failureMessage = extractFailureMessage(afterMatch)
                        failures.add(
                            TestFailure(
                                testName = testName,
                                className = className,
                                message = failureMessage,
                            )
                        )
                    }
                    "SKIPPED" -> skipped++
                }
            }

            return TestResults(
                totalTests = passed + failed + skipped,
                passed = passed,
                failed = failed,
                skipped = skipped,
                failures = failures,
            )
        }

        /**
         * Extract the failure message from Gradle output following a FAILED test line.
         * Looks for indented lines containing exception/assertion info.
         */
        private fun extractFailureMessage(textAfterFailure: String): String? {
            val lines = textAfterFailure.lines()
            val messageLines = mutableListOf<String>()
            for (line in lines) {
                // Stop at the next test result or blank line after collecting some
                if (line.isBlank() && messageLines.isNotEmpty()) break
                if (TEST_RESULT_LINE.containsMatchIn(line)) break
                if (line.startsWith("> Task")) break
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("at ")) {
                    messageLines.add(trimmed)
                }
                // Limit to avoid capturing excessive output
                if (messageLines.size >= 5) break
            }
            return messageLines.joinToString("\n").ifEmpty { null }
        }

        fun parseTestResultsFromXml(testResultsDir: File): TestResults? {
            if (!testResultsDir.exists() || !testResultsDir.isDirectory) return null

            val xmlFiles = testResultsDir.listFiles { file -> file.extension == "xml" }
                ?: return null

            if (xmlFiles.isEmpty()) return null

            var totalTests = 0
            var totalFailures = 0
            var totalSkipped = 0
            val failures = mutableListOf<TestFailure>()

            val dbf = DocumentBuilderFactory.newInstance()

            for (xmlFile in xmlFiles) {
                val doc = dbf.newDocumentBuilder().parse(xmlFile)
                val testSuite = doc.documentElement

                totalTests += testSuite.getAttribute("tests").toIntOrNull() ?: 0
                totalFailures += testSuite.getAttribute("failures").toIntOrNull() ?: 0
                totalSkipped += testSuite.getAttribute("skipped").toIntOrNull() ?: 0

                val testCases = testSuite.getElementsByTagName("testcase")
                for (i in 0 until testCases.length) {
                    val testCase = testCases.item(i) as Element
                    val failureNodes = testCase.getElementsByTagName("failure")
                    if (failureNodes.length > 0) {
                        val failureElement = failureNodes.item(0) as Element
                        failures.add(
                            TestFailure(
                                testName = testCase.getAttribute("name"),
                                className = testCase.getAttribute("classname"),
                                message = failureElement.getAttribute("message").ifEmpty { null },
                                stackTrace = failureElement.textContent?.ifEmpty { null },
                            )
                        )
                    }
                }
            }

            val passed = totalTests - totalFailures - totalSkipped
            return TestResults(
                totalTests = totalTests,
                passed = passed,
                failed = totalFailures,
                skipped = totalSkipped,
                failures = failures,
            )
        }
    }
}
