package eval.scoring

import eval.model.SemanticJudgment
import eval.provider.ModelProvider
import eval.provider.ProjectContext

/**
 * LLM-as-judge for semantic comparison between generated and reference code.
 *
 * Evaluates three dimensions on a 1–5 scale:
 * - Approach similarity
 * - Behavioral equivalence
 * - Completeness
 *
 * Plus a free-text divergence explanation.
 */
class SemanticJudge(
    private val judgeProvider: ModelProvider,
) {
    suspend fun judge(
        generatedCode: Map<String, String>,
        referenceSolution: Map<String, String>,
    ): SemanticJudgment {
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
        referenceSolution: Map<String, String>,
    ): String = buildString {
        appendLine("You are a code comparison judge. Compare the Generated Code against the Reference Solution.")
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

        appendLine("## Reference Solution")
        appendLine()
        for ((path, content) in referenceSolution) {
            appendLine("### $path")
            appendLine("```kotlin")
            appendLine(content.trim())
            appendLine("```")
            appendLine()
        }

        appendLine("## Instructions")
        appendLine()
        appendLine("Rate the generated code compared to the reference solution on three dimensions.")
        appendLine("Each dimension is scored 1–5.")
        appendLine()
        appendLine("### Approach Similarity (1–5)")
        appendLine("Did the generated code use the same design pattern and architectural approach?")
        appendLine("1 = Completely different approach")
        appendLine("2 = Different overall approach with some shared elements")
        appendLine("3 = Similar high-level approach but significant differences")
        appendLine("4 = Same approach with minor variations")
        appendLine("5 = Essentially the same design — differences are cosmetic only")
        appendLine()
        appendLine("### Behavioral Equivalence (1–5)")
        appendLine("Ignoring style, does the generated code produce the same behavior?")
        appendLine("1 = Fundamentally different behavior")
        appendLine("2 = Some correct behavior but major gaps")
        appendLine("3 = Core behavior matches but edge cases differ")
        appendLine("4 = Behavior matches in nearly all cases")
        appendLine("5 = Functionally identical")
        appendLine()
        appendLine("### Completeness (1–5)")
        appendLine("Does the generated code handle all cases the reference handles?")
        appendLine("1 = Handles almost none of the reference's cases")
        appendLine("2 = Handles some basic cases")
        appendLine("3 = Handles main cases but misses secondary ones")
        appendLine("4 = Handles nearly all cases with minor omissions")
        appendLine("5 = Fully complete")
        appendLine()
        appendLine("Respond in this exact format:")
        appendLine("APPROACH: <number>")
        appendLine("BEHAVIORAL: <number>")
        appendLine("COMPLETENESS: <number>")
        appendLine("DIVERGENCE: <explanation of where and why the code diverges>")
    }

    companion object {
        private val APPROACH_PATTERN = Regex("""APPROACH:\s*(\d)""")
        private val BEHAVIORAL_PATTERN = Regex("""BEHAVIORAL:\s*(\d)""")
        private val COMPLETENESS_PATTERN = Regex("""COMPLETENESS:\s*(\d)""")

        fun parseJudgeResponse(response: String): SemanticJudgment {
            val approach = APPROACH_PATTERN.find(response)
                ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 5) ?: 3
            val behavioral = BEHAVIORAL_PATTERN.find(response)
                ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 5) ?: 3
            val completeness = COMPLETENESS_PATTERN.find(response)
                ?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(1, 5) ?: 3

            val divergence = response
                .substringAfter("DIVERGENCE:", "")
                .trim()
                .ifEmpty { response.take(500) }

            return SemanticJudgment(
                approachSimilarity = approach,
                behavioralEquivalence = behavioral,
                completeness = completeness,
                divergenceExplanation = divergence,
                rawResponse = response,
            )
        }
    }
}
