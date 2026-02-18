package eval.provider

import eval.model.TokenUsage

data class ProjectContext(
    val files: Map<String, String>,
    val buildConfig: String,
    val projectStructure: String,
)

data class GeneratedCode(
    val files: Map<String, String>,
    val explanation: String? = null,
    val rawResponse: String,
    val tokenUsage: TokenUsage? = null,
)

interface ModelProvider {
    val name: String

    suspend fun generateCode(
        prompt: String,
        projectContext: ProjectContext,
        previousErrors: List<String>? = null,
    ): GeneratedCode
}
