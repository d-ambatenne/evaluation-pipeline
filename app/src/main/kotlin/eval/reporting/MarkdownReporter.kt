package eval.reporting

import eval.model.*
import eval.scoring.FailureCategorizer
import eval.scoring.Scorer
import java.io.File

object MarkdownReporter {

    fun write(run: EvalRun, outputDir: File) {
        outputDir.mkdirs()
        val file = File(outputDir, "summary.md")
        file.writeText(generate(run))
    }

    fun generate(run: EvalRun): String = buildString {
        appendLine("# Eval Results: ${run.projectName}")
        appendLine("Run: ${run.timestamp}")
        appendLine()

        val models = run.models
        val taskIds = run.results.map { it.taskId }.distinct()

        // Group results by taskId -> model
        val resultMap = run.results.associateBy { "${it.taskId}::${it.model}" }

        // Summary table
        appendLine("## Summary Table")
        appendLine()
        append("| Task | Difficulty |")
        models.forEach { append(" $it |") }
        appendLine()
        append("|------|-----------|")
        models.forEach { append("--------|") }
        appendLine()

        // We need task difficulty — derive from results or assume we don't have TaskDefinition here
        for (taskId in taskIds) {
            append("| $taskId |  |")
            for (model in models) {
                val result = resultMap["$taskId::$model"]
                val cell = when {
                    result == null -> " - "
                    result.finalOutcome == Outcome.SUCCESS -> {
                        val n = result.metrics.attemptsToSuccess ?: "?"
                        " ✅ ($n) "
                    }
                    result.finalOutcome == Outcome.PARTIAL -> " ⚠️ "
                    else -> " ❌ "
                }
                append("$cell|")
            }
            appendLine()
        }
        appendLine()
        appendLine("Legend: ✅ (N) = succeeded in N attempts, ⚠️ = compiles but tests fail, ❌ = failed all attempts")
        appendLine()

        // Aggregate metrics per model
        appendLine("## Metrics")
        appendLine()
        append("| Metric |")
        models.forEach { append(" $it |") }
        appendLine()
        append("|--------|")
        models.forEach { append("--------|") }
        appendLine()

        val metricsByModel = models.associateWith { model ->
            Scorer.aggregate(run.results.filter { it.model == model })
        }

        appendMetricRow("First-try compile rate", models, metricsByModel) {
            formatPercent(it.firstTryCompileRate)
        }
        appendMetricRow("First-try test pass rate", models, metricsByModel) {
            formatPercent(it.firstTryTestPassRate)
        }
        appendMetricRow("Recovery rate", models, metricsByModel) {
            formatPercent(it.recoveryRate)
        }
        appendMetricRow("Avg attempts to success", models, metricsByModel) {
            it.avgAttemptsToSuccess?.let { v -> "%.1f".format(v) } ?: "N/A"
        }
        appendMetricRow("Avg time to solution", models, metricsByModel) {
            "${(it.avgTimeTotalMs / 1000).toLong()}s"
        }
        appendLine()

        // Failure distribution
        val failureCategories = run.results
            .filter { it.finalOutcome != Outcome.SUCCESS }
            .mapNotNull { result ->
                result.failureCategory ?: FailureCategorizer.categorize(result)
            }

        if (failureCategories.isNotEmpty()) {
            appendLine("## Failure Distribution")
            appendLine()
            appendLine("| Category | Count | % |")
            appendLine("|----------|-------|---|")

            val total = failureCategories.size
            val grouped = failureCategories.groupingBy { it }.eachCount()
                .entries.sortedByDescending { it.value }

            for ((category, count) in grouped) {
                val pct = (count * 100.0 / total).toInt()
                appendLine("| ${formatCategory(category)} | $count | $pct% |")
            }
            appendLine()
        }

        // Per-task details
        appendLine("## Per-Task Details")
        appendLine()
        for (taskId in taskIds) {
            appendLine("### $taskId")
            appendLine()
            for (model in models) {
                val result = resultMap["$taskId::$model"] ?: continue
                appendLine("**$model**: ${result.finalOutcome} in ${result.attempts.size} attempt(s), ${result.metrics.totalDurationMs}ms total")
                if (result.attempts.any { it.compilerErrors.isNotEmpty() }) {
                    appendLine()
                    appendLine("Compiler errors (last attempt):")
                    val lastErrors = result.attempts.last().compilerErrors
                    for (error in lastErrors.take(5)) {
                        appendLine("- `$error`")
                    }
                    if (lastErrors.size > 5) {
                        appendLine("- ... and ${lastErrors.size - 5} more")
                    }
                }
                appendLine()
            }
        }
    }

    private fun StringBuilder.appendMetricRow(
        label: String,
        models: List<String>,
        metricsByModel: Map<String, Scorer.AggregateMetrics>,
        format: (Scorer.AggregateMetrics) -> String,
    ) {
        append("| $label |")
        for (model in models) {
            val metrics = metricsByModel[model]
            append(" ${if (metrics != null) format(metrics) else "N/A"} |")
        }
        appendLine()
    }

    private fun formatPercent(value: Double): String = "${(value * 100).toInt()}%"

    private fun formatCategory(category: FailureCategory): String = when (category) {
        FailureCategory.WRONG_API_USAGE -> "Wrong API usage"
        FailureCategory.COMPILES_WRONG_BEHAVIOR -> "Compiles, wrong behavior"
        FailureCategory.NO_COMPILE_NO_RECOVERY -> "Can't self-fix from errors"
        FailureCategory.NON_IDIOMATIC -> "Non-idiomatic"
        FailureCategory.FORMATTING_MISMATCH -> "Formatting mismatch"
        FailureCategory.STALE_API -> "Stale API knowledge"
    }
}
