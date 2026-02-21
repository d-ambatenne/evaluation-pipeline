package eval.scoring

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StructuralAnalyzerTest {

    // ── Jaccard ───────────────────────────────────────────────────────

    @Test
    fun `jaccard of identical sets is 1`() {
        assertEquals(1.0, StructuralAnalyzer.jaccard(setOf("a", "b"), setOf("a", "b")))
    }

    @Test
    fun `jaccard of disjoint sets is 0`() {
        assertEquals(0.0, StructuralAnalyzer.jaccard(setOf("a"), setOf("b")))
    }

    @Test
    fun `jaccard of empty sets is 1`() {
        assertEquals(1.0, StructuralAnalyzer.jaccard(emptySet<String>(), emptySet()))
    }

    @Test
    fun `jaccard partial overlap`() {
        val result = StructuralAnalyzer.jaccard(setOf("a", "b", "c"), setOf("b", "c", "d"))
        assertEquals(0.5, result, 0.01) // 2/4
    }

    // ── Cosine Similarity ─────────────────────────────────────────────

    @Test
    fun `cosine of identical vectors is 1`() {
        assertEquals(1.0, StructuralAnalyzer.cosineSimilarity(listOf(1, 2, 3), listOf(1, 2, 3)), 0.01)
    }

    @Test
    fun `cosine of zero vectors is 1`() {
        assertEquals(1.0, StructuralAnalyzer.cosineSimilarity(listOf(0, 0), listOf(0, 0)))
    }

    @Test
    fun `cosine of orthogonal vectors is 0`() {
        assertEquals(0.0, StructuralAnalyzer.cosineSimilarity(listOf(1, 0), listOf(0, 1)), 0.01)
    }

    // ── Token Overlap ─────────────────────────────────────────────────

    @Test
    fun `extractIdentifiers strips keywords and comments`() {
        val code = """
            // this is a comment
            fun myFunction(param: String): Int {
                val result = param.length
                return result
            }
        """.trimIndent()
        val ids = StructuralAnalyzer.extractIdentifiers(code)
        assertTrue("myFunction" in ids)
        assertTrue("param" in ids)
        assertTrue("result" in ids)
        assertTrue("String" in ids)
        assertTrue("Int" in ids)
        assertTrue("length" in ids)
        // Keywords should not appear
        assertTrue("fun" !in ids)
        assertTrue("val" !in ids)
        assertTrue("return" !in ids)
    }

    @Test
    fun `extractIdentifiers strips string literals`() {
        val code = """val msg = "hello world identifier""""
        val ids = StructuralAnalyzer.extractIdentifiers(code)
        assertTrue("msg" in ids)
        assertTrue("hello" !in ids)
        assertTrue("identifier" !in ids)
    }

    @Test
    fun `tokenOverlap identical code is 1`() {
        val code = "fun greet(name: String) = println(name)"
        assertEquals(1.0, StructuralAnalyzer.computeTokenOverlap(code, code), 0.01)
    }

    @Test
    fun `tokenOverlap completely different code is low`() {
        val gen = "fun alpha(x: Int) = x + 1"
        val ref = "fun beta(y: Double) = y * 2.0"
        val overlap = StructuralAnalyzer.computeTokenOverlap(gen, ref)
        assertTrue(overlap < 0.5)
    }

    // ── Import Alignment ──────────────────────────────────────────────

    @Test
    fun `extractImports finds all imports`() {
        val code = """
            package com.example
            import io.ktor.server.routing.*
            import io.ktor.server.application.Application
            import kotlinx.serialization.Serializable
        """.trimIndent()
        val imports = StructuralAnalyzer.extractImports(code)
        assertEquals(3, imports.size)
        assertTrue("io.ktor.server.routing" in imports)
        assertTrue("io.ktor.server.application.Application" in imports)
        assertTrue("kotlinx.serialization.Serializable" in imports)
    }

    @Test
    fun `importAlignment identical imports is 1`() {
        val code = """
            import io.ktor.server.routing.*
            import kotlinx.serialization.Serializable
        """.trimIndent()
        assertEquals(1.0, StructuralAnalyzer.computeImportAlignment(code, code), 0.01)
    }

    // ── Public API Match ──────────────────────────────────────────────

    @Test
    fun `extractDeclarations finds functions and classes`() {
        val code = """
            data class Customer(val id: Int, val name: String)
            fun Routing.configureRoutes() { }
            object CustomerStore { }
            val defaultCustomer = Customer(0, "")
        """.trimIndent()
        val decls = StructuralAnalyzer.extractDeclarations(code)
        assertTrue("class Customer" in decls)
        assertTrue("fun configureRoutes" in decls)
        assertTrue("object CustomerStore" in decls)
        assertTrue("val defaultCustomer" in decls)
    }

    @Test
    fun `publicApiMatch identical declarations is 1`() {
        val code = """
            fun processOrder(order: Order): Result { }
            data class Order(val id: Int)
        """.trimIndent()
        assertEquals(1.0, StructuralAnalyzer.computePublicApiMatch(code, code), 0.01)
    }

    // ── Control Flow Similarity ───────────────────────────────────────

    @Test
    fun `countControlFlow counts constructs`() {
        val code = """
            if (x > 0) { }
            when (y) { 1 -> {} }
            for (i in items) { }
            items.map { it.name }
            items.filter { it.active }
        """.trimIndent()
        val counts = StructuralAnalyzer.countControlFlow(code)
        assertEquals(1, counts[0]) // if
        assertEquals(1, counts[1]) // when
        assertEquals(1, counts[2]) // for
        assertEquals(0, counts[3]) // while
        assertEquals(1, counts[11]) // .map
        assertEquals(1, counts[12]) // .filter
    }

    @Test
    fun `controlFlowSimilarity identical code is 1`() {
        val code = "if (x) { } for (i in list) { }"
        assertEquals(1.0, StructuralAnalyzer.computeControlFlowSimilarity(code, code), 0.01)
    }

    // ── API Version Alignment ─────────────────────────────────────────

    @Test
    fun `apiVersionAlignment matching versions is 1`() {
        val gen = "import io.ktor.server.routing.get"
        val ref = "import io.ktor.server.routing.route"
        assertEquals(1.0, StructuralAnalyzer.computeApiVersionAlignment(gen, ref), 0.01)
    }

    @Test
    fun `apiVersionAlignment mismatched versions is 0`() {
        val gen = "import io.ktor.routing.get" // old v1
        val ref = "import io.ktor.server.routing.get" // new v2
        assertEquals(0.0, StructuralAnalyzer.computeApiVersionAlignment(gen, ref), 0.01)
    }

    @Test
    fun `apiVersionAlignment no version-sensitive imports defaults to 1`() {
        val gen = "import kotlinx.serialization.Serializable"
        val ref = "import kotlinx.serialization.Serializable"
        assertEquals(1.0, StructuralAnalyzer.computeApiVersionAlignment(gen, ref), 0.01)
    }

    @Test
    fun `apiVersionAlignment partial match`() {
        val gen = """
            import io.ktor.server.routing.get
            import io.ktor.application.Application
        """.trimIndent()
        val ref = """
            import io.ktor.server.routing.get
            import io.ktor.server.application.Application
        """.trimIndent()
        // routing matches (both v2), application mismatches (gen v1, ref v2)
        assertEquals(0.5, StructuralAnalyzer.computeApiVersionAlignment(gen, ref), 0.01)
    }

    // ── Full analyze ──────────────────────────────────────────────────

    @Test
    fun `analyze identical code produces all 1s`() {
        val code = mapOf("Main.kt" to """
            import io.ktor.server.routing.*
            fun hello() {
                if (true) { println("hi") }
            }
        """.trimIndent())

        val result = StructuralAnalyzer.analyze(code, code)
        assertEquals(1.0, result.tokenOverlap, 0.01)
        assertEquals(1.0, result.importAlignment, 0.01)
        assertEquals(1.0, result.publicApiMatch, 0.01)
        assertEquals(1.0, result.controlFlowSimilarity, 0.01)
        assertEquals(1.0, result.apiVersionAlignment, 0.01)
    }

    @Test
    fun `analyze empty maps produces all 1s`() {
        val result = StructuralAnalyzer.analyze(emptyMap(), emptyMap())
        assertEquals(1.0, result.tokenOverlap, 0.01)
        assertEquals(1.0, result.importAlignment, 0.01)
        assertEquals(1.0, result.publicApiMatch, 0.01)
        assertEquals(1.0, result.controlFlowSimilarity, 0.01)
        assertEquals(1.0, result.apiVersionAlignment, 0.01)
    }
}
