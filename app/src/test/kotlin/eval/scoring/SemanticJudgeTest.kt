package eval.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticJudgeTest {

    @Test
    fun `parses well-formed judge response`() {
        val response = """
APPROACH: 4
BEHAVIORAL: 5
COMPLETENESS: 4
DIVERGENCE: Both implementations use the Ktor routing DSL. The generated
code uses a slightly different error handling pattern — try/catch vs
status pages plugin.
        """.trimIndent()

        val result = SemanticJudge.parseJudgeResponse(response)
        assertEquals(4, result.approachSimilarity)
        assertEquals(5, result.behavioralEquivalence)
        assertEquals(4, result.completeness)
        assertTrue(result.divergenceExplanation.contains("Ktor routing DSL"))
    }

    @Test
    fun `parses scores at boundary values`() {
        val response = "APPROACH: 1\nBEHAVIORAL: 1\nCOMPLETENESS: 1\nDIVERGENCE: Completely different"
        val result = SemanticJudge.parseJudgeResponse(response)
        assertEquals(1, result.approachSimilarity)
        assertEquals(1, result.behavioralEquivalence)
        assertEquals(1, result.completeness)
    }

    @Test
    fun `parses max scores`() {
        val response = "APPROACH: 5\nBEHAVIORAL: 5\nCOMPLETENESS: 5\nDIVERGENCE: Identical"
        val result = SemanticJudge.parseJudgeResponse(response)
        assertEquals(5, result.approachSimilarity)
        assertEquals(5, result.behavioralEquivalence)
        assertEquals(5, result.completeness)
    }

    @Test
    fun `clamps out-of-range scores`() {
        val response = "APPROACH: 9\nBEHAVIORAL: 0\nCOMPLETENESS: 7\nDIVERGENCE: x"
        val result = SemanticJudge.parseJudgeResponse(response)
        assertEquals(5, result.approachSimilarity)
        assertEquals(1, result.behavioralEquivalence)
        assertEquals(5, result.completeness)
    }

    @Test
    fun `defaults to 3 when no scores found`() {
        val result = SemanticJudge.parseJudgeResponse("Some random response without markers.")
        assertEquals(3, result.approachSimilarity)
        assertEquals(3, result.behavioralEquivalence)
        assertEquals(3, result.completeness)
    }

    @Test
    fun `divergence falls back to raw response when no marker`() {
        val response = "APPROACH: 4\nBEHAVIORAL: 4\nCOMPLETENESS: 4\nThe code is similar overall."
        val result = SemanticJudge.parseJudgeResponse(response)
        assertEquals(4, result.approachSimilarity)
        // No DIVERGENCE: marker, so falls back to raw response prefix
        assertTrue(result.divergenceExplanation.isNotEmpty())
    }

    @Test
    fun `rawResponse is preserved`() {
        val response = "APPROACH: 3\nBEHAVIORAL: 3\nCOMPLETENESS: 3\nDIVERGENCE: Minor differences"
        val result = SemanticJudge.parseJudgeResponse(response)
        assertEquals(response, result.rawResponse)
    }
}
