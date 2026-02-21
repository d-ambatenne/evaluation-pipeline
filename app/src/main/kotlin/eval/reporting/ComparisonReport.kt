package eval.reporting

import eval.model.*
import eval.scoring.FailureCategorizer
import java.io.File

/**
 * Cross-model comparison focusing on where models diverge:
 * same task, different outcomes. Highlights which compiler errors
 * were recoverable by which models.
 */
object ComparisonReport {

    fun write(run: EvalRun, outputDir: File) {
        outputDir.mkdirs()
        val file = File(outputDir, "comparison.md")
        file.writeText(generate(run))
    }

    fun generate(run: EvalRun): String = buildString {
        appendLine("# Cross-Model Comparison: ${run.projectName}")
        appendLine("Run: ${run.timestamp}")
        appendLine()

        val models = run.models
        if (models.size < 2) {
            appendLine("_Comparison requires at least 2 models._")
            return@buildString
        }

        val taskIds = run.results.map { it.taskId }.distinct()
        val resultMap = run.results.associateBy { "${it.taskId}::${it.model}" }

        // Find divergent tasks — where models had different outcomes
        val divergent = taskIds.filter { taskId ->
            val outcomes = models.mapNotNull { resultMap["$taskId::$it"]?.finalOutcome }
            outcomes.distinct().size > 1
        }

        appendLine("## Divergent Outcomes")
        appendLine()
        if (divergent.isEmpty()) {
            appendLine("All models produced the same outcome on every task.")
            appendLine()
        } else {
            appendLine("Tasks where models had different outcomes:")
            appendLine()
            for (taskId in divergent) {
                appendLine("### $taskId")
                appendLine()
                appendLine("| Model | Outcome | Attempts | First-try compile | Recovered |")
                appendLine("|-------|---------|----------|-------------------|-----------|")
                for (model in models) {
                    val r = resultMap["$taskId::$model"]
                    if (r != null) {
                        appendLine("| $model | ${formatOutcome(r.finalOutcome)} | ${r.attempts.size} | ${yesNo(r.metrics.firstTryCompile)} | ${yesNo(r.metrics.recoveredFromError)} |")
                    }
                }
                appendLine()

                // Show error details for failures
                for (model in models) {
                    val r = resultMap["$taskId::$model"] ?: continue
                    if (r.finalOutcome == Outcome.SUCCESS) continue
                    val errors = r.attempts.flatMap { it.compilerErrors }.distinct()
                    if (errors.isNotEmpty()) {
                        appendLine("**$model** errors:")
                        for (err in errors.take(5)) {
                            appendLine("- `$err`")
                        }
                        if (errors.size > 5) appendLine("- ... and ${errors.size - 5} more")
                        appendLine()
                    }
                }
            }
        }

        // Recovery comparison — which models recovered from compiler errors
        appendLine("## Error Recovery Comparison")
        appendLine()
        appendLine("| Model | Tasks with errors | Recovered | Recovery rate |")
        appendLine("|-------|-------------------|-----------|---------------|")
        for (model in models) {
            val modelResults = run.results.filter { it.model == model }
            val hadErrors = modelResults.count { !it.metrics.firstTryCompile }
            val recovered = modelResults.count { it.metrics.recoveredFromError }
            val rate = if (hadErrors > 0) "${(recovered * 100 / hadErrors)}%" else "N/A"
            appendLine("| $model | $hadErrors | $recovered | $rate |")
        }
        appendLine()

        // Failure category comparison
        appendLine("## Failure Categories by Model")
        appendLine()
        val allCategories = FailureCategory.entries
        append("| Category |")
        models.forEach { append(" $it |") }
        appendLine()
        append("|----------|")
        models.forEach { append("--------|") }
        appendLine()

        for (category in allCategories) {
            append("| ${formatCategory(category)} |")
            for (model in models) {
                val count = run.results
                    .filter { it.model == model && it.finalOutcome != Outcome.SUCCESS }
                    .count { r ->
                        (r.failureCategory ?: FailureCategorizer.categorize(r)) == category
                    }
                append(" $count |")
            }
            appendLine()
        }
        appendLine()

        // Semantic divergence — tasks where models took different approaches
        val hasSemantic = run.results.any { it.semanticComparison != null }
        if (hasSemantic) {
            appendLine("## Semantic Similarity")
            appendLine()
            append("| Task |")
            models.forEach { append(" $it |") }
            appendLine()
            append("|------|")
            models.forEach { append("--------|") }
            appendLine()

            for (taskId in taskIds) {
                append("| $taskId |")
                for (model in models) {
                    val r = resultMap["$taskId::$model"]
                    val score = r?.semanticComparison?.compositeSimilarity
                    val cell = if (score != null) " %.2f ".format(score) else " - "
                    append("$cell|")
                }
                appendLine()
            }
            appendLine()

            // Highlight tasks where models took different approaches (approach < 3)
            val approachDivergent = taskIds.filter { taskId ->
                val scores = models.mapNotNull { model ->
                    resultMap["$taskId::$model"]?.semanticComparison?.semanticJudgment?.approachSimilarity
                }
                scores.any { it < 3 }
            }
            if (approachDivergent.isNotEmpty()) {
                appendLine("### Different Approaches Detected")
                appendLine()
                appendLine("Tasks where at least one model took a fundamentally different approach (score < 3):")
                appendLine()
                for (taskId in approachDivergent) {
                    appendLine("- **$taskId**")
                    for (model in models) {
                        val sj = resultMap["$taskId::$model"]?.semanticComparison?.semanticJudgment
                        if (sj != null && sj.approachSimilarity < 3) {
                            appendLine("  - $model (approach=${sj.approachSimilarity}): ${sj.divergenceExplanation.take(200)}")
                        }
                    }
                }
                appendLine()
            }
        }

        // Head-to-head wins
        appendLine("## Head-to-Head")
        appendLine()
        appendLine("| Task |")
        for (i in models.indices) {
            for (j in i + 1 until models.size) {
                append("| ${models[i]} vs ${models[j]} |")
            }
        }
        appendLine()

        for (taskId in taskIds) {
            append("| $taskId ")
            for (i in models.indices) {
                for (j in i + 1 until models.size) {
                    val a = resultMap["$taskId::${models[i]}"]
                    val b = resultMap["$taskId::${models[j]}"]
                    val winner = compareResults(a, b, models[i], models[j])
                    append("| $winner ")
                }
            }
            appendLine("|")
        }
        appendLine()
    }

    private fun compareResults(a: EvalResult?, b: EvalResult?, nameA: String, nameB: String): String {
        if (a == null || b == null) return "-"
        val scoreA = outcomeScore(a.finalOutcome)
        val scoreB = outcomeScore(b.finalOutcome)
        return when {
            scoreA > scoreB -> nameA
            scoreB > scoreA -> nameB
            // Same outcome — compare attempts to success
            else -> {
                val attA = a.metrics.attemptsToSuccess ?: Int.MAX_VALUE
                val attB = b.metrics.attemptsToSuccess ?: Int.MAX_VALUE
                when {
                    attA < attB -> nameA
                    attB < attA -> nameB
                    else -> "Tie"
                }
            }
        }
    }

    private fun outcomeScore(outcome: Outcome): Int = when (outcome) {
        Outcome.SUCCESS -> 3
        Outcome.PARTIAL -> 2
        Outcome.FAILURE -> 1
    }

    private fun formatOutcome(outcome: Outcome): String = when (outcome) {
        Outcome.SUCCESS -> "SUCCESS"
        Outcome.PARTIAL -> "PARTIAL"
        Outcome.FAILURE -> "FAILURE"
    }

    private fun formatCategory(category: FailureCategory): String = when (category) {
        FailureCategory.WRONG_API_USAGE -> "Wrong API usage"
        FailureCategory.COMPILES_WRONG_BEHAVIOR -> "Compiles, wrong behavior"
        FailureCategory.NO_COMPILE_NO_RECOVERY -> "Can't self-fix"
        FailureCategory.NON_IDIOMATIC -> "Non-idiomatic"
        FailureCategory.FORMATTING_MISMATCH -> "Formatting mismatch"
        FailureCategory.STALE_API -> "Stale API"
    }

    private fun yesNo(value: Boolean) = if (value) "Yes" else "No"
}
