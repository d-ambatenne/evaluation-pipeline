package eval.scoring

import eval.model.SemanticComparison
import eval.model.StructuralMetrics
import eval.runner.ProjectSandbox

/**
 * Orchestrates semantic comparison between generated code and the reference solution.
 *
 * - Layer 1 (structural): always runs — deterministic regex/text analysis
 * - Layer 2 (semantic judge): optional — LLM-as-judge evaluation
 *
 * Produces a composite similarity score in [0.0, 1.0].
 */
class SemanticComparer(
    private val semanticJudge: SemanticJudge? = null,
) {
    /**
     * Compare generated code against the reference solution on the given branch.
     *
     * @param generated  file path → code content from the model's final attempt
     * @param referenceBranch  git branch containing the reference solution
     * @param sandbox  project sandbox for reading branch files
     * @return comparison result, or null if reference files can't be found
     */
    suspend fun compare(
        generated: Map<String, String>,
        referenceBranch: String,
        sandbox: ProjectSandbox,
    ): SemanticComparison? {
        if (generated.isEmpty()) return null

        // Read reference files from the task branch (same paths the model generated)
        val reference = generated.keys.associateWith { path ->
            sandbox.readBranchFile(referenceBranch, path)
        }.filterValues { it != null }.mapValues { it.value!! }

        if (reference.isEmpty()) return null

        // Layer 1: structural metrics
        val structural = StructuralAnalyzer.analyze(generated, reference)

        // Layer 2: semantic judge (optional)
        val judgment = semanticJudge?.judge(generated, reference)

        val composite = computeComposite(structural, judgment)

        return SemanticComparison(
            structuralMetrics = structural,
            semanticJudgment = judgment,
            compositeSimilarity = composite,
        )
    }

    companion object {
        private const val STRUCTURAL_WEIGHT = 0.40
        private const val SEMANTIC_WEIGHT = 0.60

        /** Weights for individual structural metrics (must sum to 1.0). */
        private val STRUCTURAL_METRIC_WEIGHTS = doubleArrayOf(
            0.25, // tokenOverlap
            0.20, // importAlignment
            0.25, // publicApiMatch
            0.15, // controlFlowSimilarity
            0.15, // apiVersionAlignment
        )

        fun computeComposite(
            structural: StructuralMetrics,
            judgment: eval.model.SemanticJudgment?,
        ): Double {
            val structuralValues = doubleArrayOf(
                structural.tokenOverlap,
                structural.importAlignment,
                structural.publicApiMatch,
                structural.controlFlowSimilarity,
                structural.apiVersionAlignment,
            )

            val structuralScore = structuralValues.zip(STRUCTURAL_METRIC_WEIGHTS.toList())
                .sumOf { (value, weight) -> value * weight }

            if (judgment == null) {
                // No semantic judge — structural metrics are 100% of the score
                return structuralScore
            }

            // Normalize semantic scores from 1–5 to 0.0–1.0
            val semanticAvg = listOf(
                judgment.approachSimilarity,
                judgment.behavioralEquivalence,
                judgment.completeness,
            ).average()
            val semanticNormalized = (semanticAvg - 1.0) / 4.0

            return STRUCTURAL_WEIGHT * structuralScore + SEMANTIC_WEIGHT * semanticNormalized
        }
    }
}
