package eval.scoring

import eval.model.Outcome
import eval.model.Attempt
import eval.model.TokenUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ScorerTest {

    @Test
    fun `computeMetrics first try success`() {
        val attempts = listOf(
            Attempt(
                attemptNumber = 1,
                generatedCode = mapOf("a.kt" to "code"),
                compileSuccess = true,
                testSuccess = true,
                durationMs = 5000,
            )
        )
        val metrics = Scorer.computeMetrics(attempts, 5000)

        assertTrue(metrics.firstTryCompile)
        assertTrue(metrics.firstTryTestPass)
        assertEquals(1, metrics.attemptsToSuccess)
        assertEquals(5000, metrics.totalDurationMs)
        assertFalse(metrics.recoveredFromError)
    }

    @Test
    fun `computeMetrics recovered from error on second attempt`() {
        val attempts = listOf(
            Attempt(
                attemptNumber = 1,
                generatedCode = mapOf("a.kt" to "bad"),
                compileSuccess = false,
                compilerErrors = listOf("error"),
                testSuccess = false,
                durationMs = 3000,
            ),
            Attempt(
                attemptNumber = 2,
                generatedCode = mapOf("a.kt" to "good"),
                compileSuccess = true,
                testSuccess = true,
                durationMs = 4000,
            ),
        )
        val metrics = Scorer.computeMetrics(attempts, 7000)

        assertFalse(metrics.firstTryCompile)
        assertFalse(metrics.firstTryTestPass)
        assertEquals(2, metrics.attemptsToSuccess)
        assertEquals(7000, metrics.totalDurationMs)
        assertTrue(metrics.recoveredFromError)
    }

    @Test
    fun `computeMetrics total failure`() {
        val attempts = listOf(
            Attempt(
                attemptNumber = 1,
                generatedCode = mapOf("a.kt" to "bad1"),
                compileSuccess = false,
                testSuccess = false,
                durationMs = 2000,
            ),
            Attempt(
                attemptNumber = 2,
                generatedCode = mapOf("a.kt" to "bad2"),
                compileSuccess = false,
                testSuccess = false,
                durationMs = 2000,
            ),
        )
        val metrics = Scorer.computeMetrics(attempts, 4000)

        assertFalse(metrics.firstTryCompile)
        assertFalse(metrics.firstTryTestPass)
        assertNull(metrics.attemptsToSuccess)
        assertFalse(metrics.recoveredFromError)
    }

    @Test
    fun `computeMetrics partial - compiles but tests fail`() {
        val attempts = listOf(
            Attempt(
                attemptNumber = 1,
                generatedCode = mapOf("a.kt" to "compiles"),
                compileSuccess = true,
                testSuccess = false,
                durationMs = 5000,
            ),
        )
        val metrics = Scorer.computeMetrics(attempts, 5000)

        assertTrue(metrics.firstTryCompile)
        assertFalse(metrics.firstTryTestPass)
        assertNull(metrics.attemptsToSuccess)
    }

    @Test
    fun `computeMetrics empty attempts`() {
        val metrics = Scorer.computeMetrics(emptyList(), 0)

        assertFalse(metrics.firstTryCompile)
        assertFalse(metrics.firstTryTestPass)
        assertNull(metrics.attemptsToSuccess)
        assertFalse(metrics.recoveredFromError)
    }

    @Test
    fun `determineFinalOutcome SUCCESS when any attempt has test success`() {
        val attempts = listOf(
            Attempt(1, emptyMap(), false, emptyList(), false, null, 1000),
            Attempt(2, emptyMap(), true, emptyList(), true, null, 1000),
        )
        assertEquals(Outcome.SUCCESS, Scorer.determineFinalOutcome(attempts))
    }

    @Test
    fun `determineFinalOutcome PARTIAL when compiles but tests fail`() {
        val attempts = listOf(
            Attempt(1, emptyMap(), true, emptyList(), false, null, 1000),
            Attempt(2, emptyMap(), true, emptyList(), false, null, 1000),
        )
        assertEquals(Outcome.PARTIAL, Scorer.determineFinalOutcome(attempts))
    }

    @Test
    fun `determineFinalOutcome FAILURE when nothing compiles`() {
        val attempts = listOf(
            Attempt(1, emptyMap(), false, listOf("error"), false, null, 1000),
            Attempt(2, emptyMap(), false, listOf("error"), false, null, 1000),
        )
        assertEquals(Outcome.FAILURE, Scorer.determineFinalOutcome(attempts))
    }

    // --- Tests for outcome-aware determineFinalOutcome ---

    @Test
    fun `determineFinalOutcome with PARTIAL expected - PARTIAL becomes SUCCESS`() {
        val attempts = listOf(
            Attempt(1, emptyMap(), true, emptyList(), false, null, 1000),
        )
        assertEquals(
            Outcome.SUCCESS,
            Scorer.determineFinalOutcome(attempts, Outcome.PARTIAL),
        )
    }

    @Test
    fun `determineFinalOutcome with PARTIAL expected - FAILURE stays FAILURE`() {
        val attempts = listOf(
            Attempt(1, emptyMap(), false, listOf("error"), false, null, 1000),
        )
        assertEquals(
            Outcome.FAILURE,
            Scorer.determineFinalOutcome(attempts, Outcome.FAILURE),
        )
    }

    @Test
    fun `determineFinalOutcome with PARTIAL expected - SUCCESS stays SUCCESS`() {
        val attempts = listOf(
            Attempt(1, emptyMap(), true, emptyList(), true, null, 1000),
        )
        assertEquals(
            Outcome.SUCCESS,
            Scorer.determineFinalOutcome(attempts, Outcome.PARTIAL),
        )
    }

    @Test
    fun `determineFinalOutcome with SUCCESS expected - PARTIAL stays PARTIAL`() {
        val attempts = listOf(
            Attempt(1, emptyMap(), true, emptyList(), false, null, 1000),
        )
        assertEquals(
            Outcome.PARTIAL,
            Scorer.determineFinalOutcome(attempts, Outcome.SUCCESS),
        )
    }

    // --- Token usage aggregation tests ---

    @Test
    fun `computeMetrics sums token usage across attempts`() {
        val attempts = listOf(
            Attempt(
                attemptNumber = 1,
                generatedCode = mapOf("a.kt" to "bad"),
                compileSuccess = false,
                testSuccess = false,
                durationMs = 3000,
                tokenUsage = TokenUsage(inputTokens = 1000, outputTokens = 500),
            ),
            Attempt(
                attemptNumber = 2,
                generatedCode = mapOf("a.kt" to "good"),
                compileSuccess = true,
                testSuccess = true,
                durationMs = 4000,
                tokenUsage = TokenUsage(inputTokens = 1200, outputTokens = 600),
            ),
        )
        val metrics = Scorer.computeMetrics(attempts, 7000)

        assertEquals(2200, metrics.totalInputTokens)
        assertEquals(1100, metrics.totalOutputTokens)
    }

    @Test
    fun `computeMetrics handles null token usage`() {
        val attempts = listOf(
            Attempt(
                attemptNumber = 1,
                generatedCode = mapOf("a.kt" to "code"),
                compileSuccess = true,
                testSuccess = true,
                durationMs = 5000,
            ),
        )
        val metrics = Scorer.computeMetrics(attempts, 5000)

        assertEquals(0, metrics.totalInputTokens)
        assertEquals(0, metrics.totalOutputTokens)
    }

    @Test
    fun `computeMetrics handles mixed null and present token usage`() {
        val attempts = listOf(
            Attempt(
                attemptNumber = 1,
                generatedCode = mapOf("a.kt" to "bad"),
                compileSuccess = false,
                testSuccess = false,
                durationMs = 3000,
            ),
            Attempt(
                attemptNumber = 2,
                generatedCode = mapOf("a.kt" to "good"),
                compileSuccess = true,
                testSuccess = true,
                durationMs = 4000,
                tokenUsage = TokenUsage(inputTokens = 800, outputTokens = 400),
            ),
        )
        val metrics = Scorer.computeMetrics(attempts, 7000)

        assertEquals(800, metrics.totalInputTokens)
        assertEquals(400, metrics.totalOutputTokens)
    }
}
