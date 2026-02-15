package eval.scoring

import eval.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FailureCategorizerTest {

    private fun makeResult(
        outcome: Outcome,
        attempts: List<Attempt>,
        failureCategory: FailureCategory? = null,
    ) = EvalResult(
        taskId = "test",
        model = "test-model",
        timestamp = "2025-01-01T00:00:00Z",
        attempts = attempts,
        finalOutcome = outcome,
        metrics = EvalMetrics(false, false, null, 1000, false),
        failureCategory = failureCategory,
    )

    @Test
    fun `SUCCESS returns null`() {
        val result = makeResult(
            Outcome.SUCCESS,
            listOf(Attempt(1, emptyMap(), true, emptyList(), true, null, 1000)),
        )
        assertNull(FailureCategorizer.categorize(result))
    }

    @Test
    fun `compiles but tests fail returns COMPILES_WRONG_BEHAVIOR`() {
        val result = makeResult(
            Outcome.PARTIAL,
            listOf(Attempt(1, emptyMap(), true, emptyList(), false, null, 1000)),
        )
        assertEquals(FailureCategory.COMPILES_WRONG_BEHAVIOR, FailureCategorizer.categorize(result))
    }

    @Test
    fun `unresolved reference errors return WRONG_API_USAGE`() {
        val result = makeResult(
            Outcome.FAILURE,
            listOf(
                Attempt(1, emptyMap(), false, listOf("Unresolved reference: respondText"), false, null, 1000),
                Attempt(2, emptyMap(), false, listOf("Unresolved reference: respondText"), false, null, 1000),
            ),
        )
        assertEquals(FailureCategory.WRONG_API_USAGE, FailureCategorizer.categorize(result))
    }

    @Test
    fun `deprecated API errors return STALE_API`() {
        val result = makeResult(
            Outcome.FAILURE,
            listOf(
                Attempt(1, emptyMap(), false, listOf("'foo' is deprecated. Use 'bar' instead"), false, null, 1000),
            ),
        )
        assertEquals(FailureCategory.STALE_API, FailureCategorizer.categorize(result))
    }

    @Test
    fun `no compile no recovery returns NO_COMPILE_NO_RECOVERY`() {
        val result = makeResult(
            Outcome.FAILURE,
            listOf(
                Attempt(1, emptyMap(), false, listOf("Type mismatch"), false, null, 1000),
                Attempt(2, emptyMap(), false, listOf("Type mismatch"), false, null, 1000),
            ),
        )
        assertEquals(FailureCategory.NO_COMPILE_NO_RECOVERY, FailureCategorizer.categorize(result))
    }

    @Test
    fun `empty attempts returns null`() {
        val result = makeResult(Outcome.FAILURE, emptyList())
        assertNull(FailureCategorizer.categorize(result))
    }
}
