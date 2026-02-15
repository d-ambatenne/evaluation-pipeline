package eval.scoring

import eval.model.Attempt
import eval.model.EvalMetrics
import eval.model.Outcome

object Scorer {

    fun computeMetrics(attempts: List<Attempt>, totalDurationMs: Long): EvalMetrics {
        val firstAttempt = attempts.firstOrNull()
        val successIndex = attempts.indexOfFirst { it.testSuccess }

        return EvalMetrics(
            firstTryCompile = firstAttempt?.compileSuccess ?: false,
            firstTryTestPass = firstAttempt?.let { it.compileSuccess && it.testSuccess } ?: false,
            attemptsToSuccess = if (successIndex >= 0) successIndex + 1 else null,
            totalDurationMs = totalDurationMs,
            recoveredFromError = successIndex > 0,
        )
    }

    fun determineFinalOutcome(attempts: List<Attempt>): Outcome = when {
        attempts.any { it.testSuccess } -> Outcome.SUCCESS
        attempts.any { it.compileSuccess } -> Outcome.PARTIAL
        else -> Outcome.FAILURE
    }

    /**
     * Compute aggregate metrics across multiple EvalResults for a single model.
     */
    data class AggregateMetrics(
        val totalTasks: Int,
        val successes: Int,
        val partials: Int,
        val failures: Int,
        val firstTryCompileRate: Double,
        val firstTryTestPassRate: Double,
        val recoveryRate: Double,
        val avgAttemptsToSuccess: Double?,
        val avgTimeTotalMs: Double,
    )

    fun aggregate(results: List<eval.model.EvalResult>): AggregateMetrics {
        if (results.isEmpty()) return AggregateMetrics(0, 0, 0, 0, 0.0, 0.0, 0.0, null, 0.0)

        val successes = results.count { it.finalOutcome == Outcome.SUCCESS }
        val partials = results.count { it.finalOutcome == Outcome.PARTIAL }
        val failures = results.count { it.finalOutcome == Outcome.FAILURE }

        val firstTryCompiles = results.count { it.metrics.firstTryCompile }
        val firstTryPasses = results.count { it.metrics.firstTryTestPass }

        val failedFirst = results.count { !it.metrics.firstTryTestPass }
        val recovered = results.count { it.metrics.recoveredFromError }
        val recoveryRate = if (failedFirst > 0) recovered.toDouble() / failedFirst else 0.0

        val successfulAttempts = results.mapNotNull { it.metrics.attemptsToSuccess }
        val avgAttempts = if (successfulAttempts.isNotEmpty()) successfulAttempts.average() else null

        return AggregateMetrics(
            totalTasks = results.size,
            successes = successes,
            partials = partials,
            failures = failures,
            firstTryCompileRate = firstTryCompiles.toDouble() / results.size,
            firstTryTestPassRate = firstTryPasses.toDouble() / results.size,
            recoveryRate = recoveryRate,
            avgAttemptsToSuccess = avgAttempts,
            avgTimeTotalMs = results.map { it.metrics.totalDurationMs.toDouble() }.average(),
        )
    }
}
