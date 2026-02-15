package eval.context

import eval.provider.ProjectContext

/**
 * Assembles the final prompt sent to model providers by combining
 * the task description (TASK.md) with project context.
 */
object PromptAssembler {

    /**
     * Build the prompt for a first attempt (no prior errors).
     */
    fun assembleFirstAttempt(
        taskDescription: String,
        context: ProjectContext,
    ): String = buildString {
        appendLine("## Task")
        appendLine()
        appendLine(taskDescription.trim())
        appendLine()

        appendLine("## Project Structure")
        appendLine()
        appendLine("```")
        appendLine(context.projectStructure)
        appendLine("```")
        appendLine()

        appendLine("## Build Configuration")
        appendLine()
        appendLine(context.buildConfig)
        appendLine()

        if (context.files.isNotEmpty()) {
            appendLine("## Source Files")
            appendLine()
            for ((filepath, content) in context.files) {
                appendLine("### $filepath")
                val lang = languageTag(filepath)
                appendLine("```$lang")
                appendLine(content.trim())
                appendLine("```")
                appendLine()
            }
        }
    }.trim()

    /**
     * Build the prompt for a retry attempt after compile failure.
     */
    fun assembleRetryAttempt(
        taskDescription: String,
        compilerErrors: List<String>,
        previousCode: Map<String, String>,
        context: ProjectContext,
    ): String = buildString {
        appendLine("## Task")
        appendLine()
        appendLine(taskDescription.trim())
        appendLine()

        appendLine("## Previous Attempt Failed")
        appendLine()
        appendLine("The code you generated did not compile. Here are the errors:")
        appendLine()
        appendLine("```")
        for (error in compilerErrors) {
            appendLine(error)
        }
        appendLine("```")
        appendLine()

        appendLine("## Your Previous Code")
        appendLine()
        for ((filepath, content) in previousCode) {
            appendLine("### $filepath")
            val lang = languageTag(filepath)
            appendLine("```$lang")
            appendLine(content.trim())
            appendLine("```")
            appendLine()
        }

        appendLine("## Project Context")
        appendLine()
        appendLine("### Project Structure")
        appendLine("```")
        appendLine(context.projectStructure)
        appendLine("```")
        appendLine()

        if (context.files.isNotEmpty()) {
            appendLine("### Source Files")
            appendLine()
            for ((filepath, content) in context.files) {
                appendLine("#### $filepath")
                val lang = languageTag(filepath)
                appendLine("```$lang")
                appendLine(content.trim())
                appendLine("```")
                appendLine()
            }
        }

        appendLine("Please fix the issues and provide the corrected files.")
    }.trim()

    private fun languageTag(filepath: String): String = when {
        filepath.endsWith(".kt") -> "kotlin"
        filepath.endsWith(".kts") -> "kotlin"
        filepath.endsWith(".java") -> "java"
        filepath.endsWith(".xml") -> "xml"
        filepath.endsWith(".toml") -> "toml"
        filepath.endsWith(".yaml") || filepath.endsWith(".yml") -> "yaml"
        filepath.endsWith(".json") -> "json"
        filepath.endsWith(".properties") -> "properties"
        else -> ""
    }
}
