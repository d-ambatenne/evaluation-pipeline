package eval.model

import kotlinx.serialization.Serializable

@Serializable
enum class FailureCategory {
    WRONG_API_USAGE,
    COMPILES_WRONG_BEHAVIOR,
    NO_COMPILE_NO_RECOVERY,
    NON_IDIOMATIC,
    FORMATTING_MISMATCH,
    STALE_API,
}
