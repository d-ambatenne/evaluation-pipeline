package eval

import eval.provider.ClaudeProvider
import eval.provider.GeminiProvider
import eval.provider.ModelProvider
import eval.provider.OpenAIProvider
import eval.runner.EvalRunner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

fun main(args: Array<String>) {
    val config = parseArgs(args)
    val providers = resolveProviders(config.models)

    if (providers.isEmpty()) {
        System.err.println("Error: No model providers available. Set API key environment variables or use --models.")
        System.exit(1)
    }

    val runner = EvalRunner(
        repoPath = File(config.repo),
        providers = providers,
        taskFilter = config.tasks,
        maxAttemptsOverride = config.maxAttempts,
        parallel = config.parallel,
        dryRun = config.dryRun,
    )

    val result = runBlocking { runner.run() }

    val outputDir = File(config.output)
    outputDir.mkdirs()

    val json = Json { prettyPrint = true }
    val resultFile = File(outputDir, "results.json")
    resultFile.writeText(json.encodeToString(result))
    println("Results written to ${resultFile.absolutePath}")
}

private data class CliConfig(
    val repo: String,
    val tasks: Set<String>? = null,
    val models: Set<String>? = null,
    val maxAttempts: Int? = null,
    val output: String = "./results",
    val parallel: Boolean = false,
    val dryRun: Boolean = false,
)

private fun parseArgs(args: Array<String>): CliConfig {
    var repo: String? = null
    var tasks: Set<String>? = null
    var models: Set<String>? = null
    var maxAttempts: Int? = null
    var output = "./results"
    var parallel = false
    var dryRun = false

    val iter = args.iterator()
    while (iter.hasNext()) {
        when (val arg = iter.next()) {
            "--repo" -> repo = iter.next()
            "--tasks" -> tasks = iter.next().split(",").map { it.trim() }.toSet()
            "--models" -> models = iter.next().split(",").map { it.trim() }.toSet()
            "--max-attempts" -> maxAttempts = iter.next().toInt()
            "--output" -> output = iter.next()
            "--parallel" -> parallel = true
            "--dry-run" -> dryRun = true
            "--help", "-h" -> {
                printUsage()
                System.exit(0)
            }
            else -> {
                System.err.println("Unknown option: $arg")
                printUsage()
                System.exit(1)
            }
        }
    }

    if (repo == null) {
        System.err.println("Error: --repo is required")
        printUsage()
        System.exit(1)
    }

    return CliConfig(
        repo = repo!!,
        tasks = tasks,
        models = models,
        maxAttempts = maxAttempts,
        output = output,
        parallel = parallel,
        dryRun = dryRun,
    )
}

private fun resolveProviders(modelFilter: Set<String>?): List<ModelProvider> {
    val available = mutableListOf<ModelProvider>()

    // Only instantiate providers whose auth tokens are set
    if (System.getenv("ANTHROPIC_AUTH_TOKEN") != null || System.getenv("ANTHROPIC_API_KEY") != null) {
        available.add(ClaudeProvider())
    }
    if (System.getenv("OPENAI_AUTH_TOKEN") != null || System.getenv("OPENAI_API_KEY") != null) {
        available.add(OpenAIProvider())
    }
    if (System.getenv("GEMINI_API_KEY") != null) {
        available.add(GeminiProvider())
    }

    if (modelFilter != null) {
        return available.filter { it.name in modelFilter }
    }
    return available
}

private fun printUsage() {
    println("""
        |Usage: kotlin-eval-pipeline [options]
        |  --repo <path>           Path to local clone of target repository (required)
        |  --tasks <id,id,...>     Run only specific tasks (default: all)
        |  --models <name,...>     Run only specific models (default: all with API keys set)
        |  --max-attempts <n>      Override max attempts (default: from tasks.json)
        |  --output <dir>          Output directory for results (default: ./results)
        |  --parallel              Run models in parallel (default: sequential)
        |  --dry-run               Validate setup without calling models
        |  --help, -h              Show this help message
    """.trimMargin())
}
