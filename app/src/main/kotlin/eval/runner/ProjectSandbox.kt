package eval.runner

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages an isolated working copy of a target repository for evaluation.
 *
 * Uses a single clone with git checkout/clean for resets rather than
 * fresh clones per task, preserving Gradle daemon/caches.
 */
class ProjectSandbox private constructor(
    val workDir: File,
    private val mainBranch: String,
) {
    companion object {
        /**
         * Clone a remote repository into a temp directory.
         */
        fun clone(repository: String, mainBranch: String = "main"): ProjectSandbox {
            val workDir = createTempWorkDir()
            git(workDir.parentFile, "clone", "--branch", mainBranch, repository, workDir.name)
            // Fetch all branches so we can read TASK.md from any branch
            git(workDir, "fetch", "--all")
            return ProjectSandbox(workDir, mainBranch)
        }

        /**
         * Use an existing local clone. Creates a worktree to avoid
         * modifying the original repo.
         */
        fun fromLocal(localRepoPath: File, mainBranch: String = "main"): ProjectSandbox {
            val workDir = createTempWorkDir()
            // Copy the repo so we don't disturb the original
            localRepoPath.copyRecursively(workDir, overwrite = true)
            git(workDir, "checkout", mainBranch)
            git(workDir, "clean", "-fd")
            return ProjectSandbox(workDir, mainBranch)
        }

        private fun createTempWorkDir(): File {
            val tempBase = File(System.getProperty("java.io.tmpdir"), "eval-sandbox")
            tempBase.mkdirs()
            val workDir = File(tempBase, "repo-${System.currentTimeMillis()}")
            workDir.mkdirs()
            return workDir
        }

        private fun git(workDir: File, vararg args: String): String {
            val command = listOf("git") + args.toList()
            val process = ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val finished = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                throw RuntimeException("Git command timed out: ${command.joinToString(" ")}")
            }
            if (process.exitValue() != 0) {
                throw RuntimeException("Git command failed (exit ${process.exitValue()}): ${command.joinToString(" ")}\n$output")
            }
            return output.trim()
        }
    }

    /**
     * Read TASK.md from a task branch without checking it out.
     */
    fun readTaskDescription(branch: String): String {
        return git(workDir, "show", "$branch:TASK.md")
    }

    /**
     * Reset the working copy to the main branch clean state.
     */
    fun resetToMain() {
        git(workDir, "checkout", mainBranch)
        git(workDir, "clean", "-fd")
        git(workDir, "checkout", ".")
    }

    /**
     * Apply generated code files to the working copy.
     * Creates parent directories as needed.
     */
    fun applyGeneratedCode(files: Map<String, String>) {
        for ((relativePath, content) in files) {
            val file = File(workDir, relativePath)
            file.parentFile.mkdirs()
            file.writeText(content)
        }
    }

    /**
     * List files matching a glob pattern relative to the working directory.
     */
    fun resolveGlob(pattern: String): List<File> {
        val matcher = workDir.toPath().fileSystem.getPathMatcher("glob:$pattern")
        return workDir.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val relativePath = workDir.toPath().relativize(file.toPath())
                matcher.matches(relativePath)
            }
            .toList()
    }

    /**
     * Read a file relative to the working directory.
     */
    fun readFile(relativePath: String): String? {
        val file = File(workDir, relativePath)
        return if (file.exists()) file.readText() else null
    }

    /**
     * Build a tree-like listing of the project structure, excluding
     * .git, build, and .gradle directories.
     */
    fun projectStructure(): String {
        val sb = StringBuilder()
        buildTree(workDir, "", sb, isRoot = true)
        return sb.toString()
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
     * Clean up the sandbox working directory.
     */
    fun cleanup() {
        workDir.deleteRecursively()
    }
}
