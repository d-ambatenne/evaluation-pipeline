package eval.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EvalResultTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    @Test
    fun `EvalResult serialization round-trip`() {
        val result = EvalResult(
            taskId = "first-tests",
            model = "claude-sonnet-4-20250514",
            timestamp = "2025-07-15T10:30:00Z",
            attempts = listOf(
                Attempt(
                    attemptNumber = 1,
                    generatedCode = mapOf("src/test/kotlin/AppTest.kt" to "fun test() {}"),
                    compileSuccess = true,
                    testSuccess = true,
                    testResults = TestResults(
                        totalTests = 2,
                        passed = 2,
                        failed = 0,
                        skipped = 0,
                    ),
                    durationMs = 5000,
                )
            ),
            finalOutcome = Outcome.SUCCESS,
            metrics = EvalMetrics(
                firstTryCompile = true,
                firstTryTestPass = true,
                attemptsToSuccess = 1,
                totalDurationMs = 5000,
                recoveredFromError = false,
            ),
        )

        val encoded = json.encodeToString(result)
        val decoded = json.decodeFromString<EvalResult>(encoded)
        assertEquals(result, decoded)
    }

    @Test
    fun `EvalResult with failure category`() {
        val result = EvalResult(
            taskId = "crud-endpoints",
            model = "gpt-4o",
            timestamp = "2025-07-15T11:00:00Z",
            attempts = listOf(
                Attempt(
                    attemptNumber = 1,
                    generatedCode = mapOf("src/main/kotlin/Routes.kt" to "// bad code"),
                    compileSuccess = false,
                    compilerErrors = listOf("e: Unresolved reference: respondText"),
                    testSuccess = false,
                    durationMs = 3000,
                )
            ),
            finalOutcome = Outcome.FAILURE,
            metrics = EvalMetrics(
                firstTryCompile = false,
                firstTryTestPass = false,
                totalDurationMs = 3000,
                recoveredFromError = false,
            ),
            failureCategory = FailureCategory.WRONG_API_USAGE,
        )

        val encoded = json.encodeToString(result)
        val decoded = json.decodeFromString<EvalResult>(encoded)
        assertEquals(FailureCategory.WRONG_API_USAGE, decoded.failureCategory)
        assertEquals(Outcome.FAILURE, decoded.finalOutcome)
    }

    @Test
    fun `null failureCategory when success`() {
        val result = EvalResult(
            taskId = "t",
            model = "m",
            timestamp = "2025-01-01T00:00:00Z",
            attempts = emptyList(),
            finalOutcome = Outcome.SUCCESS,
            metrics = EvalMetrics(
                firstTryCompile = true,
                firstTryTestPass = true,
                attemptsToSuccess = 1,
                totalDurationMs = 0,
                recoveredFromError = false,
            ),
        )
        val encoded = json.encodeToString(result)
        val decoded = json.decodeFromString<EvalResult>(encoded)
        assertNull(decoded.failureCategory)
    }
}
