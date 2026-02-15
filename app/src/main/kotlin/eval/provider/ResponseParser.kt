package eval.provider

/**
 * Parses model responses to extract generated file contents.
 *
 * Expected format:
 * ```
 * FILE: path/to/File.kt
 * ```kotlin
 * // code here
 * ```
 * ```
 *
 * Handles edge cases: varied markers, different code fence languages,
 * responses wrapped in outer code blocks, single-file responses without FILE: marker.
 */
object ResponseParser {

    private val FILE_MARKER = Regex("""(?:^|\n)\s*(?:#{1,3}\s*)?(?:FILE|File|file)\s*:\s*(.+)""")
    private val CODE_FENCE_OPEN = Regex("""```\w*\s*""")
    private val CODE_FENCE_CLOSE = Regex("""^```\s*$""", RegexOption.MULTILINE)

    data class ParsedResponse(
        val files: Map<String, String>,
        val rawResponse: String,
    )

    fun parse(response: String): ParsedResponse {
        val unwrapped = unwrapOuterCodeBlock(response)
        val files = parseFileBlocks(unwrapped)
        return ParsedResponse(files = files, rawResponse = response)
    }

    private fun parseFileBlocks(text: String): Map<String, String> {
        val results = mutableMapOf<String, String>()
        val markers = FILE_MARKER.findAll(text).toList()

        if (markers.isEmpty()) {
            // Single-file response without FILE: marker — try to extract from the whole text
            val code = extractFirstCodeBlock(text)
            if (code != null) {
                // Can't determine file path — use a placeholder
                return mapOf("UNKNOWN_FILE" to code)
            }
            return emptyMap()
        }

        for ((index, marker) in markers.withIndex()) {
            val filePath = marker.groupValues[1].trim()
            val sectionStart = marker.range.last + 1
            val sectionEnd = if (index + 1 < markers.size) markers[index + 1].range.first else text.length
            val section = text.substring(sectionStart, sectionEnd)
            val code = extractFirstCodeBlock(section)
            if (code != null) {
                results[filePath] = code
            }
        }

        return results
    }

    private fun extractFirstCodeBlock(text: String): String? {
        val openMatch = CODE_FENCE_OPEN.find(text) ?: return null
        val afterOpen = openMatch.range.last + 1
        val closeMatch = CODE_FENCE_CLOSE.find(text.substring(afterOpen)) ?: return null
        val code = text.substring(afterOpen, afterOpen + closeMatch.range.first)
        return code.trimEnd('\n')
    }

    /**
     * If the entire response is wrapped in a single outer code block, unwrap it.
     */
    private fun unwrapOuterCodeBlock(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return text

        val firstNewline = trimmed.indexOf('\n')
        if (firstNewline == -1) return text

        val lastFence = trimmed.lastIndexOf("```")
        if (lastFence <= firstNewline) return text

        // Check that the last ``` is at the very end
        val afterLastFence = trimmed.substring(lastFence + 3).trim()
        if (afterLastFence.isNotEmpty()) return text

        val inner = trimmed.substring(firstNewline + 1, lastFence)

        // Only unwrap if the inner content contains FILE: markers
        if (FILE_MARKER.containsMatchIn(inner)) {
            return inner
        }

        return text
    }
}
