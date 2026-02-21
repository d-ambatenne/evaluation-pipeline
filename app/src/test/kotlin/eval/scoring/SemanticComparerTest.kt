package eval.scoring

import eval.model.SemanticJudgment
import eval.model.StructuralMetrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticComparerTest {

    @Test
    fun `composite score with structural only uses full structural weight`() {
        val structural = StructuralMetrics(
            tokenOverlap = 1.0,
            importAlignment = 1.0,
            publicApiMatch = 1.0,
            controlFlowSimilarity = 1.0,
            apiVersionAlignment = 1.0,
        )
        val composite = SemanticComparer.computeComposite(structural, null)
        assertEquals(1.0, composite, 0.01)
    }

    @Test
    fun `composite score with all zeros structural is 0`() {
        val structural = StructuralMetrics(
            tokenOverlap = 0.0,
            importAlignment = 0.0,
            publicApiMatch = 0.0,
            controlFlowSimilarity = 0.0,
            apiVersionAlignment = 0.0,
        )
        val composite = SemanticComparer.computeComposite(structural, null)
        assertEquals(0.0, composite, 0.01)
    }

    @Test
    fun `composite score with judge blends structural and semantic`() {
        val structural = StructuralMetrics(
            tokenOverlap = 0.8,
            importAlignment = 0.8,
            publicApiMatch = 0.8,
            controlFlowSimilarity = 0.8,
            apiVersionAlignment = 0.8,
        )
        val judgment = SemanticJudgment(
            approachSimilarity = 5,
            behavioralEquivalence = 5,
            completeness = 5,
            divergenceExplanation = "Identical",
            rawResponse = "APPROACH: 5\nBEHAVIORAL: 5\nCOMPLETENESS: 5\nDIVERGENCE: Identical",
        )
        val composite = SemanticComparer.computeComposite(structural, judgment)
        // structural = 0.8, semantic = (5-1)/4 = 1.0
        // composite = 0.40 * 0.8 + 0.60 * 1.0 = 0.32 + 0.60 = 0.92
        assertEquals(0.92, composite, 0.01)
    }

    @Test
    fun `composite score with minimum judge scores`() {
        val structural = StructuralMetrics(
            tokenOverlap = 0.5,
            importAlignment = 0.5,
            publicApiMatch = 0.5,
            controlFlowSimilarity = 0.5,
            apiVersionAlignment = 0.5,
        )
        val judgment = SemanticJudgment(
            approachSimilarity = 1,
            behavioralEquivalence = 1,
            completeness = 1,
            divergenceExplanation = "Completely different",
            rawResponse = "",
        )
        val composite = SemanticComparer.computeComposite(structural, judgment)
        // structural = 0.5, semantic = (1-1)/4 = 0.0
        // composite = 0.40 * 0.5 + 0.60 * 0.0 = 0.20
        assertEquals(0.20, composite, 0.01)
    }

    @Test
    fun `composite is between 0 and 1`() {
        val structural = StructuralMetrics(
            tokenOverlap = 0.6,
            importAlignment = 0.7,
            publicApiMatch = 0.4,
            controlFlowSimilarity = 0.9,
            apiVersionAlignment = 0.5,
        )
        val judgment = SemanticJudgment(
            approachSimilarity = 3,
            behavioralEquivalence = 4,
            completeness = 2,
            divergenceExplanation = "Some differences",
            rawResponse = "",
        )
        val composite = SemanticComparer.computeComposite(structural, judgment)
        assertTrue(composite in 0.0..1.0)
    }
}
