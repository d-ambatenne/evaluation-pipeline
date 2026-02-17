package eval.model

import kotlinx.serialization.Serializable

@Serializable
data class TaskManifest(
    val projectName: String,
    val repository: String,
    val mainBranch: String = "main",
    val tasks: List<TaskDefinition>,
)

@Serializable
data class TaskDefinition(
    val id: String,
    val branch: String,
    val title: String,
    val difficulty: Difficulty,
    val tags: List<String> = emptyList(),
    val verification: Verification,
    val maxAttempts: Int = 3,
    val contextFiles: List<String> = emptyList(),
    val expectedOutcome: Outcome = Outcome.SUCCESS,
)

@Serializable
data class Verification(
    val compileTask: String,
    val testTask: String,
    val requiredTests: List<String> = emptyList(),
)

@Serializable
enum class Difficulty {
    TRIVIAL, EASY, MEDIUM, HARD, EXPERT
}
