package eval.reporting

import eval.model.*
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val TOKEN_USAGE_1 = TokenUsage(inputTokens = 1500, outputTokens = 800)
private val TOKEN_USAGE_2 = TokenUsage(inputTokens = 2000, outputTokens = 1200)

class MarkdownReporterTest {

    private val sampleRun = EvalRun(
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
                    Attempt(1, mapOf("a.kt" to "bad"), false, listOf("Unresolved reference"), false, null, 3000),
                    Attempt(2, mapOf("a.kt" to "good"), true, emptyList(), true, null, 4000),
                ),
                finalOutcome = Outcome.SUCCESS,
                metrics = EvalMetrics(false, false, 2, 7000, true),
            ),
            EvalResult(
                taskId = "crud-impl",
                model = "claude-sonnet",
                timestamp = "2025-07-15T10:32:00Z",
                attempts = listOf(
                    Attempt(1, mapOf("b.kt" to "code"), true, emptyList(), false, null, 6000),
                ),
                finalOutcome = Outcome.PARTIAL,
                metrics = EvalMetrics(true, false, null, 6000, false),
            ),
            EvalResult(
                taskId = "crud-impl",
                model = "gpt-4o",
                timestamp = "2025-07-15T10:33:00Z",
                attempts = listOf(
                    Attempt(1, mapOf("b.kt" to "bad"), false, listOf("Type mismatch"), false, null, 3000),
                ),
                finalOutcome = Outcome.FAILURE,
                metrics = EvalMetrics(false, false, null, 3000, false),
            ),
        ),
    )

    @Test
    fun `generates report with title and timestamp`() {
        val md = MarkdownReporter.generate(sampleRun)
        assertContains(md, "# Eval Results: ktor-workshop-2025")
        assertContains(md, "Run: 2025-07-15T10:30:00Z")
    }

    @Test
    fun `generates summary table with all tasks and models`() {
        val md = MarkdownReporter.generate(sampleRun)
        assertContains(md, "## Summary Table")
        assertContains(md, "| first-tests |")
        assertContains(md, "| crud-impl |")
        assertContains(md, "claude-sonnet")
        assertContains(md, "gpt-4o")
    }

    @Test
    fun `summary table shows success with attempt count`() {
        val md = MarkdownReporter.generate(sampleRun)
        // Claude succeeded first-tests in 1 attempt
        assertContains(md, "✅ (1)")
        // GPT-4o succeeded first-tests in 2 attempts
        assertContains(md, "✅ (2)")
    }

    @Test
    fun `summary table shows failure and partial indicators`() {
        val md = MarkdownReporter.generate(sampleRun)
        assertContains(md, "❌")
        assertContains(md, "⚠️")
    }

    @Test
    fun `generates metrics section`() {
        val md = MarkdownReporter.generate(sampleRun)
        assertContains(md, "## Metrics")
        assertContains(md, "First-try compile rate")
        assertContains(md, "First-try test pass rate")
        assertContains(md, "Recovery rate")
        assertContains(md, "Avg attempts to success")
    }

    @Test
    fun `generates failure distribution`() {
        val md = MarkdownReporter.generate(sampleRun)
        assertContains(md, "## Failure Distribution")
    }

    @Test
    fun `generates per-task details`() {
        val md = MarkdownReporter.generate(sampleRun)
        assertContains(md, "## Per-Task Details")
        assertContains(md, "### first-tests")
        assertContains(md, "### crud-impl")
    }

    @Test
    fun `includes compiler errors in per-task details`() {
        val md = MarkdownReporter.generate(sampleRun)
        assertContains(md, "Type mismatch")
    }

    @Test
    fun `handles empty run`() {
        val emptyRun = sampleRun.copy(results = emptyList(), models = emptyList())
        val md = MarkdownReporter.generate(emptyRun)
        assertTrue(md.contains("# Eval Results: ktor-workshop-2025"))
    }

    @Test
    fun `generates code delta section when results have codeDelta`() {
        val runWithDelta = sampleRun.copy(
            results = sampleRun.results.map { result ->
                if (result.taskId == "first-tests" && result.model == "claude-sonnet") {
                    result.copy(codeDelta = CodeDelta(generatedLoc = 45, referenceLoc = 40, delta = 5))
                } else {
                    result
                }
            },
        )
        val md = MarkdownReporter.generate(runWithDelta)
        assertContains(md, "## Code Delta (LOC)")
        assertContains(md, "45/40 (+5)")
        assertContains(md, "Format: generated/reference (delta)")
    }

    @Test
    fun `no code delta section when no results have codeDelta`() {
        val md = MarkdownReporter.generate(sampleRun)
        assertFalse(md.contains("## Code Delta (LOC)"))
    }

    @Test
    fun `code delta shown in per-task details`() {
        val runWithDelta = sampleRun.copy(
            results = sampleRun.results.map { result ->
                if (result.taskId == "first-tests" && result.model == "claude-sonnet") {
                    result.copy(codeDelta = CodeDelta(generatedLoc = 30, referenceLoc = 50, delta = -20))
                } else {
                    result
                }
            },
        )
        val md = MarkdownReporter.generate(runWithDelta)
        assertContains(md, "LOC: 30 generated / 50 reference (-20)")
    }

    @Test
    fun `generates total tokens row in metrics table`() {
        val runWithTokens = sampleRun.copy(
            results = sampleRun.results.map { result ->
                if (result.taskId == "first-tests" && result.model == "claude-sonnet") {
                    result.copy(
                        attempts = listOf(
                            Attempt(1, mapOf("a.kt" to "code"), true, emptyList(), true, null, 5000, TOKEN_USAGE_1),
                        ),
                        metrics = EvalMetrics(true, true, 1, 5000, false, totalInputTokens = 1500, totalOutputTokens = 800),
                    )
                } else {
                    result
                }
            },
        )
        val md = MarkdownReporter.generate(runWithTokens)
        assertContains(md, "Total tokens")
        assertContains(md, "2,300")
    }

    @Test
    fun `shows per-attempt token counts in per-task details`() {
        val runWithTokens = sampleRun.copy(
            results = sampleRun.results.map { result ->
                if (result.taskId == "first-tests" && result.model == "gpt-4o") {
                    result.copy(
                        attempts = listOf(
                            Attempt(1, mapOf("a.kt" to "bad"), false, listOf("Unresolved reference"), false, null, 3000, TOKEN_USAGE_1),
                            Attempt(2, mapOf("a.kt" to "good"), true, emptyList(), true, null, 4000, TOKEN_USAGE_2),
                        ),
                        metrics = EvalMetrics(false, false, 2, 7000, true, totalInputTokens = 3500, totalOutputTokens = 2000),
                    )
                } else {
                    result
                }
            },
        )
        val md = MarkdownReporter.generate(runWithTokens)
        assertContains(md, "Attempt 1: 1,500 in / 800 out")
        assertContains(md, "Attempt 2: 2,000 in / 1,200 out")
    }
}
