package eval.context

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContextBuilderTest {

    private fun withTempProject(block: (File) -> Unit) {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "ctx-builder-test-${System.currentTimeMillis()}")
        tempDir.mkdirs()
        try {
            // Create a minimal project structure
            File(tempDir, "build.gradle.kts").writeText("plugins { kotlin(\"jvm\") }")
            File(tempDir, "settings.gradle.kts").writeText("rootProject.name = \"test\"")
            File(tempDir, "gradle").mkdirs()
            File(tempDir, "gradle/libs.versions.toml").writeText("[versions]\nkotlin = \"2.1.10\"")

            File(tempDir, "src/main/kotlin").mkdirs()
            File(tempDir, "src/main/kotlin/App.kt").writeText("fun main() { println(\"hello\") }")
            File(tempDir, "src/main/kotlin/Routes.kt").writeText("fun routes() {}")

            File(tempDir, "src/test/kotlin").mkdirs()
            File(tempDir, "src/test/kotlin/AppTest.kt").writeText("fun testApp() {}")

            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolves glob patterns and reads file contents`() {
        withTempProject { projectDir ->
            val builder = ContextBuilder(projectDir)
            val context = builder.build(listOf("src/main/kotlin/**/*.kt"))

            assertEquals(2, context.files.size)
            assertTrue(context.files.containsKey("src/main/kotlin/App.kt"))
            assertTrue(context.files.containsKey("src/main/kotlin/Routes.kt"))
            assertTrue(context.files["src/main/kotlin/App.kt"]!!.contains("fun main()"))
        }
    }

    @Test
    fun `includes build configuration`() {
        withTempProject { projectDir ->
            val builder = ContextBuilder(projectDir)
            val context = builder.build(emptyList())

            assertTrue(context.buildConfig.contains("build.gradle.kts"))
            assertTrue(context.buildConfig.contains("settings.gradle.kts"))
            assertTrue(context.buildConfig.contains("libs.versions.toml"))
        }
    }

    @Test
    fun `builds project structure tree`() {
        withTempProject { projectDir ->
            val builder = ContextBuilder(projectDir)
            val context = builder.build(emptyList())

            assertTrue(context.projectStructure.contains("src/"))
            assertTrue(context.projectStructure.contains("build.gradle.kts"))
        }
    }

    @Test
    fun `handles multiple glob patterns`() {
        withTempProject { projectDir ->
            val builder = ContextBuilder(projectDir)
            val context = builder.build(listOf(
                "src/main/kotlin/**/*.kt",
                "src/test/kotlin/**/*.kt",
            ))

            assertEquals(3, context.files.size)
            assertTrue(context.files.containsKey("src/test/kotlin/AppTest.kt"))
        }
    }

    @Test
    fun `empty patterns returns no source files`() {
        withTempProject { projectDir ->
            val builder = ContextBuilder(projectDir)
            val context = builder.build(emptyList())

            assertTrue(context.files.isEmpty())
        }
    }

    @Test
    fun `truncates files when exceeding token budget`() {
        withTempProject { projectDir ->
            // Create a large file
            val largeContent = "x".repeat(10_000)
            File(projectDir, "src/main/kotlin/Large.kt").writeText(largeContent)

            // Very small budget (100 tokens = 400 chars)
            val builder = ContextBuilder(projectDir, tokenBudget = 100)
            val context = builder.build(listOf("src/main/kotlin/Large.kt"))

            // The file should be truncated or omitted
            if (context.files.containsKey("src/main/kotlin/Large.kt")) {
                assertTrue(context.files["src/main/kotlin/Large.kt"]!!.length < largeContent.length)
                assertTrue(context.files["src/main/kotlin/Large.kt"]!!.contains("[file truncated"))
            }
        }
    }
}
