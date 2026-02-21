package eval.scoring

import eval.model.StructuralMetrics

/**
 * Deterministic structural comparison between generated and reference code.
 *
 * Computes five metrics (all 0.0–1.0) using regex/text analysis — no LLM calls.
 */
object StructuralAnalyzer {

    fun analyze(
        generated: Map<String, String>,
        reference: Map<String, String>,
    ): StructuralMetrics {
        val genAll = generated.values.joinToString("\n")
        val refAll = reference.values.joinToString("\n")

        return StructuralMetrics(
            tokenOverlap = computeTokenOverlap(genAll, refAll),
            importAlignment = computeImportAlignment(genAll, refAll),
            publicApiMatch = computePublicApiMatch(genAll, refAll),
            controlFlowSimilarity = computeControlFlowSimilarity(genAll, refAll),
            apiVersionAlignment = computeApiVersionAlignment(genAll, refAll),
        )
    }

    // ── Token Overlap (Jaccard on identifiers) ────────────────────────

    private val KOTLIN_KEYWORDS = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "is", "in", "as", "try", "catch", "finally", "throw", "true", "false",
        "null", "this", "super", "it", "override", "open", "abstract", "sealed",
        "data", "enum", "companion", "private", "public", "protected", "internal",
        "suspend", "inline", "reified", "lateinit", "by", "lazy", "get", "set",
        "typealias", "annotation", "constructor", "init", "out", "vararg",
    )

    private val IDENTIFIER_PATTERN = Regex("""\b[a-zA-Z_]\w*\b""")
    private val STRING_LITERAL = Regex(""""(?:[^"\\]|\\.)*"""")
    private val LINE_COMMENT = Regex("""//.*$""", RegexOption.MULTILINE)
    private val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")

    internal fun extractIdentifiers(code: String): Set<String> {
        val cleaned = code
            .replace(BLOCK_COMMENT, " ")
            .replace(LINE_COMMENT, " ")
            .replace(STRING_LITERAL, " ")
        return IDENTIFIER_PATTERN.findAll(cleaned)
            .map { it.value }
            .filter { it !in KOTLIN_KEYWORDS }
            .toSet()
    }

    internal fun computeTokenOverlap(gen: String, ref: String): Double {
        val genTokens = extractIdentifiers(gen)
        val refTokens = extractIdentifiers(ref)
        return jaccard(genTokens, refTokens)
    }

    // ── Import Alignment (Jaccard on imports) ─────────────────────────

    private val IMPORT_PATTERN = Regex("""^\s*import\s+(.+)$""", RegexOption.MULTILINE)

    internal fun extractImports(code: String): Set<String> =
        IMPORT_PATTERN.findAll(code)
            .map { it.groupValues[1].trim().removeSuffix(".*").removeSuffix(".*") }
            .toSet()

    internal fun computeImportAlignment(gen: String, ref: String): Double {
        val genImports = extractImports(gen)
        val refImports = extractImports(ref)
        return jaccard(genImports, refImports)
    }

    // ── Public API Match (Jaccard on declarations) ────────────────────

    private val DECLARATION_PATTERN = Regex(
        """(?:^|\s)(?:(?:public|internal|open|abstract|sealed|data|override|suspend|inline)\s+)*""" +
            """(fun|class|object|interface|val|var)\s+(?:\w+\.\s*)?(\w+)""",
        RegexOption.MULTILINE,
    )

    internal fun extractDeclarations(code: String): Set<String> =
        DECLARATION_PATTERN.findAll(code)
            .map { "${it.groupValues[1]} ${it.groupValues[2]}" }
            .toSet()

    internal fun computePublicApiMatch(gen: String, ref: String): Double {
        val genDecls = extractDeclarations(gen)
        val refDecls = extractDeclarations(ref)
        return jaccard(genDecls, refDecls)
    }

    // ── Control Flow Similarity (cosine on construct counts) ──────────

    private val CONTROL_FLOW_CONSTRUCTS = listOf(
        Regex("""\bif\s*\("""),
        Regex("""\bwhen\s*[\({]"""),
        Regex("""\bfor\s*\("""),
        Regex("""\bwhile\s*\("""),
        Regex("""\btry\s*\{"""),
        Regex("""\bcatch\s*\("""),
        Regex("""\?\.\s*let\b"""),
        Regex("""\?\.\s*run\b"""),
        Regex("""\?\.\s*also\b"""),
        Regex("""\?\.\s*apply\b"""),
        Regex("""\.\s*forEach\b"""),
        Regex("""\.\s*map\b"""),
        Regex("""\.\s*filter\b"""),
    )

    internal fun countControlFlow(code: String): List<Int> =
        CONTROL_FLOW_CONSTRUCTS.map { pattern -> pattern.findAll(code).count() }

    internal fun computeControlFlowSimilarity(gen: String, ref: String): Double {
        val genCounts = countControlFlow(gen)
        val refCounts = countControlFlow(ref)
        return cosineSimilarity(genCounts, refCounts)
    }

    // ── API Version Alignment ─────────────────────────────────────────

    /**
     * Known package migrations: old → new.
     * Both directions are tracked so we can detect either version.
     */
    internal val API_MIGRATIONS: List<Pair<String, String>> = listOf(
        // Ktor 1.x → 2.x server-side
        "io.ktor.routing" to "io.ktor.server.routing",
        "io.ktor.application" to "io.ktor.server.application",
        "io.ktor.response" to "io.ktor.server.response",
        "io.ktor.request" to "io.ktor.server.request",
        "io.ktor.html" to "io.ktor.server.html",
        "io.ktor.sessions" to "io.ktor.server.sessions",
        "io.ktor.auth" to "io.ktor.server.auth",
        "io.ktor.websocket" to "io.ktor.server.websocket",
        "io.ktor.features.ContentNegotiation" to "io.ktor.server.plugins.contentnegotiation",
        "io.ktor.features.StatusPages" to "io.ktor.server.plugins.statuspages",
        "io.ktor.features.CORS" to "io.ktor.server.plugins.cors",
    )

    /**
     * Build a lookup from any known package prefix → its logical API group.
     * Both old and new map to the same group ID.
     */
    private val PACKAGE_TO_GROUP: Map<String, Int> by lazy {
        val map = mutableMapOf<String, Int>()
        for ((index, pair) in API_MIGRATIONS.withIndex()) {
            map[pair.first] = index
            map[pair.second] = index
        }
        map
    }

    internal fun computeApiVersionAlignment(gen: String, ref: String): Double {
        val genImports = extractImports(gen)
        val refImports = extractImports(ref)

        // Map each import to its API group (if known)
        fun resolveGroups(imports: Set<String>): Map<Int, String> {
            val result = mutableMapOf<Int, String>()
            for (imp in imports) {
                for ((prefix, group) in PACKAGE_TO_GROUP) {
                    if (imp.startsWith(prefix)) {
                        result[group] = prefix
                        break
                    }
                }
            }
            return result
        }

        val genGroups = resolveGroups(genImports)
        val refGroups = resolveGroups(refImports)

        // Find groups present in both
        val sharedGroups = genGroups.keys.intersect(refGroups.keys)
        if (sharedGroups.isEmpty()) return 1.0 // no version-sensitive imports to compare

        val matches = sharedGroups.count { group ->
            genGroups[group] == refGroups[group]
        }

        return matches.toDouble() / sharedGroups.size
    }

    // ── Utility functions ─────────────────────────────────────────────

    internal fun <T> jaccard(a: Set<T>, b: Set<T>): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val intersection = a.intersect(b).size
        val union = a.union(b).size
        return if (union == 0) 1.0 else intersection.toDouble() / union
    }

    internal fun cosineSimilarity(a: List<Int>, b: List<Int>): Double {
        require(a.size == b.size) { "Vectors must have the same length" }
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = Math.sqrt(normA) * Math.sqrt(normB)
        return if (denom == 0.0) 1.0 else dot / denom
    }
}
