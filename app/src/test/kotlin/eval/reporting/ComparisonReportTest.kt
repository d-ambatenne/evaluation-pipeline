package eval.reporting

import eval.model.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class ComparisonReportTest {

    private val divergentRun = EvalRun(
        id = "test-run",
        timestamp = "2025-07-15T10:30:00Z",
        projectName = "ktor-workshop-2025",
        repository = "https://github.com/example/repo",
        models = listOf("claude-sonnet", "gpt-4o"),
        results = listOf(
            EvalResult(
                taskId = "first-tests",
                model = "claude-sonnet",
                timestamp = "2025-07-15T10:30:00Z",
                attempts = listOf(
                    Attempt(1, mapOf("a.kt" to "code"), true, emptyList(), true, null, 5000),
                ),
                finalOutcome = Outcome.SUCCESS,
                metrics = EvalMetrics(true, true, 1, 5000, false),
            ),
            EvalResult(
                taskId = "first-tests",
                model = "gpt-4o",
                timestamp = "2025-07-15T10:31:00Z",
                attempts = listOf(
                    Attempt(1, mapOf("a.kt" to "bad"), false, listOf("Unresolved reference: foo"), false, null, 3000),
                    Attempt(2, mapOf("a.kt" to "bad2"), false, listOf("Unresolved reference: foo"), false, null, 3000),
                ),
                finalOutcome = Outcome.FAILURE,
                metrics = EvalMetrics(false, false, null, 6000, false),
            ),
        ),
    )

    @Test
    fun `generates report header`() {
        val md = ComparisonReport.generate(divergentRun)
        assertContains(md, "# Cross-Model Comparison: ktor-workshop-2025")
    }

    @Test
    fun `shows divergent outcomes section`() {
        val md = ComparisonReport.generate(divergentRun)
        assertContains(md, "## Divergent Outcomes")
        assertContains(md, "### first-tests")
    }

    @Test
    fun `shows model outcomes in divergent table`() {
        val md = ComparisonReport.generate(divergentRun)
        assertContains(md, "claude-sonnet")
        assertContains(md, "SUCCESS")
        assertContains(md, "FAILURE")
    }

    @Test
    fun `shows error details for failed models`() {
        val md = ComparisonReport.generate(divergentRun)
        assertContains(md, "Unresolved reference: foo")
    }

    @Test
    fun `shows error recovery comparison`() {
        val md = ComparisonReport.generate(divergentRun)
        assertContains(md, "## Error Recovery Comparison")
    }

    @Test
    fun `shows failure categories by model`() {
        val md = ComparisonReport.generate(divergentRun)
        assertContains(md, "## Failure Categories by Model")
    }

    @Test
    fun `shows head-to-head section`() {
        val md = ComparisonReport.generate(divergentRun)
        assertContains(md, "## Head-to-Head")
    }

    @Test
    fun `single model run shows message`() {
        val singleModel = divergentRun.copy(
            models = listOf("claude-sonnet"),
            results = divergentRun.results.filter { it.model == "claude-sonnet" },
        )
        val md = ComparisonReport.generate(singleModel)
        assertContains(md, "Comparison requires at least 2 models")
    }

    @Test
    fun `all same outcomes shows no divergence message`() {
        val sameOutcomes = EvalRun(
            id = "test",
            timestamp = "2025-01-01T00:00:00Z",
            projectName = "test",
            repository = "test",
            models = listOf("model-a", "model-b"),
            results = listOf(
                EvalResult("t1", "model-a", "ts", listOf(Attempt(1, emptyMap(), true, emptyList(), true, null, 1000)),
                    Outcome.SUCCESS, EvalMetrics(true, true, 1, 1000, false)),
                EvalResult("t1", "model-b", "ts", listOf(Attempt(1, emptyMap(), true, emptyList(), true, null, 1000)),
                    Outcome.SUCCESS, EvalMetrics(true, true, 1, 1000, false)),
            ),
        )
        val md = ComparisonReport.generate(sameOutcomes)
        assertContains(md, "All models produced the same outcome on every task")
    }
}
