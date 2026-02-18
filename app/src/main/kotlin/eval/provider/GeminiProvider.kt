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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class GeminiProvider(
    private val model: String = "gemini-2.0-flash",
    private val apiKey: String = System.getenv("GEMINI_API_KEY")
        ?: error("GEMINI_API_KEY environment variable not set"),
    private val client: HttpClient = defaultClient(),
) : ModelProvider {

    override val name: String get() = model

    override suspend fun generateCode(
        prompt: String,
        projectContext: ProjectContext,
        previousErrors: List<String>?,
    ): GeneratedCode {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"

        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(
                GeminiRequest(
                    system_instruction = GeminiContent(
                        parts = listOf(GeminiPart(text = SystemPrompt.SYSTEM)),
                    ),
                    contents = listOf(
                        GeminiContent(
                            parts = listOf(GeminiPart(text = prompt)),
                        ),
                    ),
                    generationConfig = GeminiGenerationConfig(maxOutputTokens = 8192),
                )
            )
        }

        val body = response.body<GeminiResponse>()
        val rawResponse = body.candidates
            ?.firstOrNull()?.content?.parts
            ?.joinToString("\n") { it.text } ?: ""

        val tokenUsage = body.usageMetadata?.let {
            TokenUsage(inputTokens = it.promptTokenCount, outputTokens = it.candidatesTokenCount)
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
private data class GeminiRequest(
    val system_instruction: GeminiContent,
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
private data class GeminiContent(
    val parts: List<GeminiPart>,
)

@Serializable
private data class GeminiPart(
    val text: String,
)

@Serializable
private data class GeminiGenerationConfig(
    val maxOutputTokens: Int,
)

@Serializable
private data class GeminiResponse(
    val candidates: List<GeminiCandidate>? = null,
    val usageMetadata: GeminiUsageMetadata? = null,
)

@Serializable
private data class GeminiUsageMetadata(
    val promptTokenCount: Int = 0,
    val candidatesTokenCount: Int = 0,
)

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent,
)
