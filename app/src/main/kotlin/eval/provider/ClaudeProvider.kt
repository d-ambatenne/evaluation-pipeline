package eval.provider

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import eval.model.TokenUsage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class ClaudeProvider(
    private val model: String = "claude-sonnet-4-20250514",
    private val baseUrl: String = System.getenv("ANTHROPIC_BASE_URL") ?: "https://api.anthropic.com",
    private val authToken: String = System.getenv("ANTHROPIC_AUTH_TOKEN")
        ?: System.getenv("ANTHROPIC_API_KEY")
        ?: error("ANTHROPIC_AUTH_TOKEN or ANTHROPIC_API_KEY environment variable not set"),
    private val client: HttpClient = defaultClient(),
) : ModelProvider {

    override val name: String get() = model

    override suspend fun generateCode(
        prompt: String,
        projectContext: ProjectContext,
        previousErrors: List<String>?,
    ): GeneratedCode {
        val response = client.post("${baseUrl.trimEnd('/')}/v1/messages") {
            header("x-api-key", authToken)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(
                ClaudeRequest(
                    model = model,
                    max_tokens = 8192,
                    system = SystemPrompt.SYSTEM,
                    messages = listOf(
                        ClaudeMessage(role = "user", content = prompt),
                    ),
                )
            )
        }

        val body = response.body<ClaudeResponse>()
        val rawResponse = body.content
            .filter { it.type == "text" }
            .joinToString("\n") { it.text }

        val tokenUsage = body.usage?.let {
            TokenUsage(inputTokens = it.inputTokens, outputTokens = it.outputTokens)
        }

        val parsed = ResponseParser.parse(rawResponse)
        return GeneratedCode(
            files = parsed.files,
            rawResponse = rawResponse,
            tokenUsage = tokenUsage,
        )
    }

    companion object {
        fun defaultClient() = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 600_000
                socketTimeoutMillis = 600_000
            }
        }
    }
}

@Serializable
private data class ClaudeRequest(
    val model: String,
    val max_tokens: Int,
    val system: String,
    val messages: List<ClaudeMessage>,
)

@Serializable
private data class ClaudeMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class ClaudeResponse(
    val content: List<ClaudeContentBlock>,
    val usage: ClaudeUsage? = null,
)

@Serializable
private data class ClaudeUsage(
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
)

@Serializable
private data class ClaudeContentBlock(
    val type: String,
    val text: String = "",
)
