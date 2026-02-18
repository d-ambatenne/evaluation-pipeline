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

class OpenAIProvider(
    private val model: String = "gpt-4o",
    private val baseUrl: String = System.getenv("OPENAI_BASE_URL") ?: "https://api.openai.com",
    private val authToken: String = System.getenv("OPENAI_AUTH_TOKEN")
        ?: System.getenv("OPENAI_API_KEY")
        ?: error("OPENAI_AUTH_TOKEN or OPENAI_API_KEY environment variable not set"),
    private val client: HttpClient = defaultClient(),
) : ModelProvider {

    override val name: String get() = model

    override suspend fun generateCode(
        prompt: String,
        projectContext: ProjectContext,
        previousErrors: List<String>?,
    ): GeneratedCode {
        val response = client.post("${baseUrl.trimEnd('/')}/v1/chat/completions") {
            header("Authorization", "Bearer $authToken")
            contentType(ContentType.Application.Json)
            setBody(
                OpenAIRequest(
                    model = model,
                    messages = listOf(
                        OpenAIMessage(role = "system", content = SystemPrompt.SYSTEM),
                        OpenAIMessage(role = "user", content = prompt),
                    ),
                    max_tokens = 8192,
                )
            )
        }

        val body = response.body<OpenAIResponse>()
        val rawResponse = body.choices.firstOrNull()?.message?.content ?: ""

        val tokenUsage = body.usage?.let {
            TokenUsage(inputTokens = it.promptTokens, outputTokens = it.completionTokens)
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
private data class OpenAIRequest(
    val model: String,
    val messages: List<OpenAIMessage>,
    val max_tokens: Int,
)

@Serializable
private data class OpenAIMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class OpenAIResponse(
    val choices: List<OpenAIChoice>,
    val usage: OpenAIUsage? = null,
)

@Serializable
private data class OpenAIUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
)

@Serializable
private data class OpenAIChoice(
    val message: OpenAIMessage,
)
