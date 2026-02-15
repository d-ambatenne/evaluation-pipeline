package eval.scoring

import eval.model.*

/**
 * Heuristic-based failure classification (v1 — rule-based).
 * Examines compiler errors, test failures, and generated code
 * to determine the root cause category.
 */
object FailureCategorizer {

    fun categorize(result: EvalResult): FailureCategory? {
        if (result.finalOutcome == Outcome.SUCCESS) return null

        val lastAttempt = result.attempts.lastOrNull() ?: return null
        val allCompilerErrors = result.attempts.flatMap { it.compilerErrors }
        val allCode = result.attempts.lastOrNull()?.generatedCode?.values?.joinToString("\n") ?: ""

        return when {
            // Compiles but tests fail
            lastAttempt.compileSuccess && !lastAttempt.testSuccess ->
                COMPILES_WRONG_BEHAVIOR

            // Never compiled across all attempts
            result.attempts.none { it.compileSuccess } -> {
                when {
                    hasStaleApiErrors(allCompilerErrors) -> STALE_API
                    hasWrongApiUsage(allCompilerErrors) -> WRONG_API_USAGE
                    else -> NO_COMPILE_NO_RECOVERY
                }
            }

            // Compiled at some point but ended in failure
            else -> {
                when {
                    hasJavaPatterns(allCode) -> NON_IDIOMATIC
                    else -> COMPILES_WRONG_BEHAVIOR
                }
            }
        }
    }

    private val COMPILES_WRONG_BEHAVIOR = FailureCategory.COMPILES_WRONG_BEHAVIOR
    private val WRONG_API_USAGE = FailureCategory.WRONG_API_USAGE
    private val STALE_API = FailureCategory.STALE_API
    private val NO_COMPILE_NO_RECOVERY = FailureCategory.NO_COMPILE_NO_RECOVERY
    private val NON_IDIOMATIC = FailureCategory.NON_IDIOMATIC

    private val UNRESOLVED_REFERENCE = Regex("""[Uu]nresolved reference""", RegexOption.IGNORE_CASE)
    private val DEPRECATED_API = Regex("""deprecated|@Deprecated""", RegexOption.IGNORE_CASE)

    private fun hasWrongApiUsage(errors: List<String>): Boolean =
        errors.any { UNRESOLVED_REFERENCE.containsMatchIn(it) }

    private fun hasStaleApiErrors(errors: List<String>): Boolean =
        errors.any { DEPRECATED_API.containsMatchIn(it) }

    /**
     * Simple heuristic: detect Java-isms in Kotlin code.
     */
    private val JAVA_PATTERNS = listOf(
        Regex("""\bSystem\.out\.println\b"""),
        Regex("""\bArrayList\s*<"""),
        Regex("""\bHashMap\s*<"""),
        Regex("""\bStringBuilder\(\)"""),
        Regex("""\b\.equals\("""),
        Regex("""\binstanceof\b"""),
        Regex("""\bvoid\b"""),
    )

    private fun hasJavaPatterns(code: String): Boolean =
        JAVA_PATTERNS.any { it.containsMatchIn(code) }
}
