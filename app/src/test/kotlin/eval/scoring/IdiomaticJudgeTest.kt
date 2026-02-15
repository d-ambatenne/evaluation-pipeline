package eval.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdiomaticJudgeTest {

    @Test
    fun `parses well-formed judge response`() {
        val response = """
SCORE: 4
REASONING: The code is mostly idiomatic Kotlin. Uses data classes and
extension functions well. Minor issue: could use `also` instead of
explicit variable assignment.
        """.trimIndent()

        val result = IdiomaticJudge.parseJudgeResponse(response)
        assertEquals(4, result.score)
        assertTrue(result.reasoning.contains("mostly idiomatic Kotlin"))
    }

    @Test
    fun `parses score at boundary values`() {
        assertEquals(1, IdiomaticJudge.parseJudgeResponse("SCORE: 1\nREASONING: Bad").score)
        assertEquals(5, IdiomaticJudge.parseJudgeResponse("SCORE: 5\nREASONING: Great").score)
    }

    @Test
    fun `clamps out-of-range scores`() {
        assertEquals(5, IdiomaticJudge.parseJudgeResponse("SCORE: 9\nREASONING: x").score)
        assertEquals(1, IdiomaticJudge.parseJudgeResponse("SCORE: 0\nREASONING: x").score)
    }

    @Test
    fun `defaults to 3 when no score found`() {
        val result = IdiomaticJudge.parseJudgeResponse("Some random response without a score.")
        assertEquals(3, result.score)
    }

    @Test
    fun `reasoning falls back to raw response when no REASONING marker`() {
        val response = "SCORE: 4\nThe code looks good overall."
        val result = IdiomaticJudge.parseJudgeResponse(response)
        assertEquals(4, result.score)
        assertTrue(result.reasoning.isNotEmpty())
    }

    @Test
    fun `rawResponse is preserved`() {
        val response = "SCORE: 3\nREASONING: Fine"
        val result = IdiomaticJudge.parseJudgeResponse(response)
        assertEquals(response, result.rawResponse)
    }
}
