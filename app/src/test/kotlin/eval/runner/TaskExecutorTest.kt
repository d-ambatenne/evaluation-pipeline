package eval.runner

import eval.model.Outcome
import eval.model.Attempt
import eval.model.EvalMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class TaskExecutorTest {

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
        val metrics = TaskExecutor.computeMetrics(attempts, 5000)

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
        val metrics = TaskExecutor.computeMetrics(attempts, 7000)

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
        val metrics = TaskExecutor.computeMetrics(attempts, 4000)

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
        val metrics = TaskExecutor.computeMetrics(attempts, 5000)

        assertTrue(metrics.firstTryCompile)
        assertFalse(metrics.firstTryTestPass)
        assertNull(metrics.attemptsToSuccess)
    }

    @Test
    fun `computeMetrics empty attempts`() {
        val metrics = TaskExecutor.computeMetrics(emptyList(), 0)

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
        assertEquals(Outcome.SUCCESS, TaskExecutor.determineFinalOutcome(attempts))
    }

    @Test
    fun `determineFinalOutcome PARTIAL when compiles but tests fail`() {
        val attempts = listOf(
            Attempt(1, emptyMap(), true, emptyList(), false, null, 1000),
            Attempt(2, emptyMap(), true, emptyList(), false, null, 1000),
        )
        assertEquals(Outcome.PARTIAL, TaskExecutor.determineFinalOutcome(attempts))
    }

    @Test
    fun `determineFinalOutcome FAILURE when nothing compiles`() {
        val attempts = listOf(
            Attempt(1, emptyMap(), false, listOf("error"), false, null, 1000),
            Attempt(2, emptyMap(), false, listOf("error"), false, null, 1000),
        )
        assertEquals(Outcome.FAILURE, TaskExecutor.determineFinalOutcome(attempts))
    }
}
