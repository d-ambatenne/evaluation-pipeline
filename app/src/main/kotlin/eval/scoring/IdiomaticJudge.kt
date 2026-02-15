package eval.scoring

import eval.provider.GeneratedCode
import eval.provider.ModelProvider
import eval.provider.ProjectContext

/**
 * LLM-as-judge for code quality (optional).
 *
 * Sends the generated code (and optionally a reference solution) to a
 * model and asks it to rate idiomatic-ness on a 1-5 scale.
 */
class IdiomaticJudge(
    private val judgeProvider: ModelProvider,
) {
    data class JudgeResult(
        val score: Int,
        val reasoning: String,
        val rawResponse: String,
    )

    suspend fun judge(
        generatedCode: Map<String, String>,
        referenceSolution: Map<String, String>? = null,
    ): JudgeResult {
        val prompt = buildPrompt(generatedCode, referenceSolution)
        val emptyContext = ProjectContext(emptyMap(), "", "")

        val response = judgeProvider.generateCode(
            prompt = prompt,
            projectContext = emptyContext,
        )

        return parseJudgeResponse(response.rawResponse)
    }

    private fun buildPrompt(
        generatedCode: Map<String, String>,
        referenceSolution: Map<String, String>?,
    ): String = buildString {
        appendLine("You are a Kotlin code quality judge. Rate the following generated code on idiomatic Kotlin usage.")
        appendLine()
        appendLine("## Generated Code")
        appendLine()
        for ((path, content) in generatedCode) {
            appendLine("### $path")
            appendLine("```kotlin")
            appendLine(content.trim())
            appendLine("```")
            appendLine()
        }

        if (referenceSolution != null) {
            appendLine("## Reference Solution")
            appendLine()
            for ((path, content) in referenceSolution) {
                appendLine("### $path")
                appendLine("```kotlin")
                appendLine(content.trim())
                appendLine("```")
                appendLine()
            }
        }

        appendLine("## Instructions")
        appendLine()
        appendLine("Rate the generated code on a scale of 1-5 for idiomatic Kotlin usage:")
        appendLine("1 = Java-style code written in Kotlin syntax")
        appendLine("2 = Mostly Java patterns with some Kotlin features")
        appendLine("3 = Mix of Java and Kotlin patterns")
        appendLine("4 = Mostly idiomatic Kotlin with minor issues")
        appendLine("5 = Fully idiomatic Kotlin")
        appendLine()
        appendLine("Respond in this exact format:")
        appendLine("SCORE: <number>")
        appendLine("REASONING: <your explanation>")
    }

    companion object {
        private val SCORE_PATTERN = Regex("""SCORE:\s*(\d)""")

        fun parseJudgeResponse(response: String): JudgeResult {
            val scoreMatch = SCORE_PATTERN.find(response)
            val score = scoreMatch?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 5) ?: 3

            val reasoning = response
                .substringAfter("REASONING:", "")
                .trim()
                .ifEmpty { response.take(500) }

            return JudgeResult(
                score = score,
                reasoning = reasoning,
                rawResponse = response,
            )
        }
    }
}
