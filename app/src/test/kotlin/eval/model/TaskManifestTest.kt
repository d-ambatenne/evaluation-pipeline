package eval.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskManifestTest {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    private val sampleManifest = TaskManifest(
        projectName = "ktor-workshop-2025",
        repository = "https://github.com/nomisRev/ktor-workshop-2025",
        mainBranch = "main",
        tasks = listOf(
            TaskDefinition(
                id = "first-tests",
                branch = "branch02",
                title = "Write first tests for the Ktor application",
                difficulty = Difficulty.EASY,
                tags = listOf("testing", "ktor"),
                verification = Verification(
                    compileTask = ":backend:compileKotlin",
                    testTask = ":backend:test",
                    requiredTests = emptyList(),
                ),
                maxAttempts = 3,
                contextFiles = listOf(
                    "backend/src/main/kotlin/**/*.kt",
                    "backend/build.gradle.kts",
                ),
            )
        ),
    )

    @Test
    fun `serialize and deserialize TaskManifest round-trip`() {
        val encoded = json.encodeToString(sampleManifest)
        val decoded = json.decodeFromString<TaskManifest>(encoded)
        assertEquals(sampleManifest, decoded)
    }

    @Test
    fun `deserialize from JSON string matching tasks json format`() {
        val jsonString = """
        {
          "projectName": "ktor-workshop-2025",
          "repository": "https://github.com/nomisRev/ktor-workshop-2025",
          "mainBranch": "main",
          "tasks": [
            {
              "id": "first-tests",
              "branch": "branch02",
              "title": "Write first tests for the Ktor application",
              "difficulty": "EASY",
              "tags": ["testing", "ktor"],
              "verification": {
                "compileTask": ":backend:compileKotlin",
                "testTask": ":backend:test",
                "requiredTests": []
              },
              "maxAttempts": 3,
              "contextFiles": [
                "backend/src/main/kotlin/**/*.kt",
                "backend/build.gradle.kts"
              ]
            }
          ]
        }
        """.trimIndent()

        val decoded = json.decodeFromString<TaskManifest>(jsonString)
        assertEquals("ktor-workshop-2025", decoded.projectName)
        assertEquals(1, decoded.tasks.size)
        assertEquals("first-tests", decoded.tasks[0].id)
        assertEquals(Difficulty.EASY, decoded.tasks[0].difficulty)
    }

    @Test
    fun `defaults are applied when optional fields omitted`() {
        val jsonString = """
        {
          "projectName": "test",
          "repository": "https://example.com/repo",
          "tasks": [
            {
              "id": "task1",
              "branch": "branch01",
              "title": "A task",
              "difficulty": "MEDIUM",
              "verification": {
                "compileTask": ":compileKotlin",
                "testTask": ":test"
              }
            }
          ]
        }
        """.trimIndent()

        val decoded = json.decodeFromString<TaskManifest>(jsonString)
        assertEquals("main", decoded.mainBranch)
        assertEquals(3, decoded.tasks[0].maxAttempts)
        assertEquals(emptyList(), decoded.tasks[0].tags)
        assertEquals(emptyList(), decoded.tasks[0].contextFiles)
        assertEquals(emptyList(), decoded.tasks[0].verification.requiredTests)
    }

    @Test
    fun `all difficulty levels serialize correctly`() {
        for (difficulty in Difficulty.entries) {
            val task = TaskDefinition(
                id = "test",
                branch = "b",
                title = "t",
                difficulty = difficulty,
                verification = Verification(":compile", ":test"),
            )
            val encoded = json.encodeToString(task)
            val decoded = json.decodeFromString<TaskDefinition>(encoded)
            assertEquals(difficulty, decoded.difficulty)
        }
    }
}
