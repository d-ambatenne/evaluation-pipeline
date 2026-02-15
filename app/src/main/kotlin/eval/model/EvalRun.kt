package eval.model

import kotlinx.serialization.Serializable

@Serializable
data class EvalRun(
    val id: String,
    val timestamp: Timestamp,
    val projectName: String,
    val repository: String,
    val models: List<String>,
    val results: List<EvalResult>,
)
