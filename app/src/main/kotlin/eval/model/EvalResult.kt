package eval.model

import kotlinx.serialization.Serializable

/** ISO-8601 timestamp string, e.g. "2025-07-15T10:30:00Z" */
typealias Timestamp = String

@Serializable
data class EvalResult(
    val taskId: String,
    val model: String,
    val timestamp: Timestamp,
    val attempts: List<Attempt>,
    val finalOutcome: Outcome,
    val metrics: EvalMetrics,
    val failureCategory: FailureCategory? = null,
)

@Serializable
data class Attempt(
    val attemptNumber: Int,
    val generatedCode: Map<String, String>,
    val compileSuccess: Boolean,
    val compilerErrors: List<String> = emptyList(),
    val testSuccess: Boolean,
    val testResults: TestResults? = null,
    val durationMs: Long,
)

@Serializable
enum class Outcome {
    SUCCESS, PARTIAL, FAILURE
}

@Serializable
data class EvalMetrics(
    val firstTryCompile: Boolean,
    val firstTryTestPass: Boolean,
    val attemptsToSuccess: Int? = null,
    val totalDurationMs: Long,
    val recoveredFromError: Boolean,
)

@Serializable
data class TestResults(
    val totalTests: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val failures: List<TestFailure> = emptyList(),
)

@Serializable
data class TestFailure(
    val testName: String,
    val className: String,
    val message: String? = null,
    val stackTrace: String? = null,
)
