package eval.context

import eval.provider.ProjectContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * Builds project context from a working directory using glob patterns.
 * Manages a token budget to avoid sending too much context to models.
 */
class ContextBuilder(
    private val workDir: File,
    private val tokenBudget: Int = DEFAULT_TOKEN_BUDGET,
) {
    companion object {
        const val DEFAULT_TOKEN_BUDGET = 30_000
        private const val CHARS_PER_TOKEN = 4
    }

    fun build(contextFilePatterns: List<String>): ProjectContext {
        val sourceFiles = resolveGlobs(contextFilePatterns)
        val buildConfig = readBuildConfig()
        val projectStructure = buildProjectStructure()

        val budgetChars = tokenBudget * CHARS_PER_TOKEN
        // Reserve space for build config and structure
        val reservedChars = buildConfig.length + projectStructure.length
        val availableForFiles = (budgetChars - reservedChars).coerceAtLeast(0)

        val files = fitFilesToBudget(sourceFiles, availableForFiles)

        return ProjectContext(
            files = files,
            buildConfig = buildConfig,
            projectStructure = projectStructure,
        )
    }

    private fun resolveGlobs(patterns: List<String>): Map<String, String> {
        val fs = FileSystems.getDefault()
        val result = mutableMapOf<String, String>()

        for (pattern in patterns) {
            // Java NIO's ** doesn't match zero directories with a trailing /,
            // so also try a version with **/ removed to cover that case.
            val matchers = buildList {
                add(fs.getPathMatcher("glob:$pattern"))
                if (pattern.contains("**/")) {
                    add(fs.getPathMatcher("glob:${pattern.replace("**/", "")}"))
                }
            }
            workDir.walkTopDown()
                .filter { it.isFile }
                .filter { file ->
                    val relativePath = workDir.toPath().relativize(file.toPath())
                    matchers.any { it.matches(relativePath) }
                }
                .forEach { file ->
                    val key = workDir.toPath().relativize(file.toPath()).toString()
                    if (key !in result) {
                        result[key] = file.readText()
                    }
                }
        }

        return result
    }

    private fun readBuildConfig(): String {
        val sb = StringBuilder()
        val buildFiles = listOf(
            "build.gradle.kts",
            "settings.gradle.kts",
            "gradle/libs.versions.toml",
        )
        for (name in buildFiles) {
            val file = File(workDir, name)
            if (file.exists()) {
                sb.appendLine("### $name")
                sb.appendLine("```")
                sb.appendLine(file.readText().trim())
                sb.appendLine("```")
                sb.appendLine()
            }
        }
        // Also look for subproject build files
        workDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val subBuild = File(dir, "build.gradle.kts")
            if (subBuild.exists()) {
                val relativePath = "${dir.name}/build.gradle.kts"
                sb.appendLine("### $relativePath")
                sb.appendLine("```")
                sb.appendLine(subBuild.readText().trim())
                sb.appendLine("```")
                sb.appendLine()
            }
        }
        return sb.toString().trim()
    }

    private fun buildProjectStructure(): String {
        val sb = StringBuilder()
        buildTree(workDir, "", sb, isRoot = true)
        return sb.toString().trim()
    }

    private fun buildTree(dir: File, indent: String, sb: StringBuilder, isRoot: Boolean = false) {
        val skipDirs = setOf(".git", "build", ".gradle", ".idea", "node_modules")
        val children = (dir.listFiles() ?: return)
            .filter { it.name !in skipDirs }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))

        for ((index, child) in children.withIndex()) {
            val isLast = index == children.size - 1
            val connector = if (isRoot) "" else if (isLast) "└── " else "├── "
            val childIndent = if (isRoot) "" else if (isLast) "$indent    " else "$indent│   "

            sb.appendLine("$indent$connector${child.name}${if (child.isDirectory) "/" else ""}")
            if (child.isDirectory) {
                buildTree(child, childIndent, sb)
            }
        }
    }

    /**
     * Fit files into the available character budget, truncating
     * least-relevant files (by size, largest truncated first).
     */
    private fun fitFilesToBudget(
        files: Map<String, String>,
        availableChars: Int,
    ): Map<String, String> {
        var totalChars = files.values.sumOf { it.length }
        if (totalChars <= availableChars) return files

        // Sort by size descending — truncate the largest files first
        val sortedEntries = files.entries.sortedByDescending { it.value.length }
        val result = mutableMapOf<String, String>()
        var remaining = availableChars

        for (entry in sortedEntries) {
            if (entry.value.length <= remaining) {
                result[entry.key] = entry.value
                remaining -= entry.value.length
            } else if (remaining > 200) {
                // Truncate this file
                val lines = entry.value.lines()
                val kept = mutableListOf<String>()
                var keptChars = 0
                for (line in lines) {
                    if (keptChars + line.length + 1 > remaining - 100) break
                    kept.add(line)
                    keptChars += line.length + 1
                }
                val omitted = lines.size - kept.size
                kept.add("[file truncated — $omitted lines omitted]")
                result[entry.key] = kept.joinToString("\n")
                remaining = 0
            }
            // else: skip file entirely
        }

        return result
    }
}
