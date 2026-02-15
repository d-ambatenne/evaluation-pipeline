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

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return GradleResult(
                exitCode = -1,
                stdout = "",
                stderr = "Gradle task timed out after ${timeoutSeconds}s",
                success = false,
            )
        }

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()

        return GradleResult(
            exitCode = process.exitValue(),
            stdout = stdout,
            stderr = stderr,
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
