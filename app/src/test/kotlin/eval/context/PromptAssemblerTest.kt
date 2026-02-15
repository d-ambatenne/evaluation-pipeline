package eval.context

import eval.provider.ProjectContext
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptAssemblerTest {

    private val sampleContext = ProjectContext(
        files = mapOf(
            "src/main/kotlin/App.kt" to "fun main() { println(\"hello\") }",
        ),
        buildConfig = "### build.gradle.kts\n```\nplugins { kotlin(\"jvm\") }\n```",
        projectStructure = "src/\n  main/\n    kotlin/\n      App.kt",
    )

    @Test
    fun `first attempt prompt contains task section`() {
        val prompt = PromptAssembler.assembleFirstAttempt(
            taskDescription = "# Write a test\nWrite a test for the App class.",
            context = sampleContext,
        )
        assertContains(prompt, "## Task")
        assertContains(prompt, "Write a test for the App class.")
    }

    @Test
    fun `first attempt prompt contains project structure`() {
        val prompt = PromptAssembler.assembleFirstAttempt(
            taskDescription = "task",
            context = sampleContext,
        )
        assertContains(prompt, "## Project Structure")
        assertContains(prompt, "App.kt")
    }

    @Test
    fun `first attempt prompt contains build configuration`() {
        val prompt = PromptAssembler.assembleFirstAttempt(
            taskDescription = "task",
            context = sampleContext,
        )
        assertContains(prompt, "## Build Configuration")
        assertContains(prompt, "build.gradle.kts")
    }

    @Test
    fun `first attempt prompt contains source files with kotlin fence`() {
        val prompt = PromptAssembler.assembleFirstAttempt(
            taskDescription = "task",
            context = sampleContext,
        )
        assertContains(prompt, "## Source Files")
        assertContains(prompt, "### src/main/kotlin/App.kt")
        assertContains(prompt, "```kotlin")
        assertContains(prompt, "fun main()")
    }

    @Test
    fun `first attempt prompt omits source files section when empty`() {
        val emptyContext = sampleContext.copy(files = emptyMap())
        val prompt = PromptAssembler.assembleFirstAttempt(
            taskDescription = "task",
            context = emptyContext,
        )
        assertFalse(prompt.contains("## Source Files"))
    }

    @Test
    fun `retry prompt contains compiler errors`() {
        val prompt = PromptAssembler.assembleRetryAttempt(
            taskDescription = "task",
            compilerErrors = listOf(
                "src/App.kt:10:5 Unresolved reference: foo",
                "src/App.kt:15:3 Type mismatch",
            ),
            previousCode = mapOf("src/App.kt" to "fun foo() {}"),
            context = sampleContext,
        )
        assertContains(prompt, "## Previous Attempt Failed")
        assertContains(prompt, "Unresolved reference: foo")
        assertContains(prompt, "Type mismatch")
    }

    @Test
    fun `retry prompt contains previous code`() {
        val prompt = PromptAssembler.assembleRetryAttempt(
            taskDescription = "task",
            compilerErrors = listOf("error"),
            previousCode = mapOf("src/App.kt" to "fun broken() {}"),
            context = sampleContext,
        )
        assertContains(prompt, "## Your Previous Code")
        assertContains(prompt, "fun broken() {}")
    }

    @Test
    fun `retry prompt ends with fix instruction`() {
        val prompt = PromptAssembler.assembleRetryAttempt(
            taskDescription = "task",
            compilerErrors = listOf("error"),
            previousCode = emptyMap(),
            context = sampleContext,
        )
        assertTrue(prompt.endsWith("Please fix the issues and provide the corrected files."))
    }

    @Test
    fun `language tag for kts files`() {
        val context = sampleContext.copy(
            files = mapOf("build.gradle.kts" to "plugins {}")
        )
        val prompt = PromptAssembler.assembleFirstAttempt("task", context)
        assertContains(prompt, "```kotlin")
    }

    @Test
    fun `language tag for toml files`() {
        val context = sampleContext.copy(
            files = mapOf("gradle/libs.versions.toml" to "[versions]")
        )
        val prompt = PromptAssembler.assembleFirstAttempt("task", context)
        assertContains(prompt, "```toml")
    }
}
