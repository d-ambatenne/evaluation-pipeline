package eval

import eval.provider.ClaudeProvider
import eval.provider.GeminiProvider
import eval.provider.ModelProvider
import eval.provider.OpenAIProvider
import eval.runner.EvalRunner
import kotlinx.coroutines.runBlocking
import java.io.File

fun main(args: Array<String>) {
    val config = parseArgs(args)
    val providers = resolveProviders(config.models)

    if (providers.isEmpty()) {
        System.err.println("Error: No model providers available. Set API key environment variables or use --models.")
        System.exit(1)
    }

    val outputDir = File(config.output)

    val runner = EvalRunner(
        repoPath = File(config.repo),
        providers = providers,
        taskFilter = config.tasks,
        maxAttemptsOverride = config.maxAttempts,
        parallel = config.parallel,
        dryRun = config.dryRun,
        outputDir = outputDir,
    )

    runBlocking { runner.run() }

    println("Results written to ${outputDir.absolutePath}")
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
    val hasAnthropicKey = System.getenv("ANTHROPIC_AUTH_TOKEN") != null || System.getenv("ANTHROPIC_API_KEY") != null
    val hasOpenAIKey = System.getenv("OPENAI_AUTH_TOKEN") != null || System.getenv("OPENAI_API_KEY") != null
    val hasGeminiKey = System.getenv("GEMINI_API_KEY") != null

    // When specific model IDs are given, create a provider per ID
    if (modelFilter != null) {
        return modelFilter.mapNotNull { modelId ->
            when {
                modelId.startsWith("claude-") && hasAnthropicKey -> ClaudeProvider(model = modelId)
                modelId.startsWith("gpt-") && hasOpenAIKey -> OpenAIProvider(model = modelId)
                modelId.startsWith("o1") && hasOpenAIKey -> OpenAIProvider(model = modelId)
                modelId.startsWith("gemini-") && hasGeminiKey -> GeminiProvider(model = modelId)
                else -> {
                    System.err.println("Warning: skipping model '$modelId' (no matching provider or missing API key)")
                    null
                }
            }
        }
    }

    // Default: one provider per available API key
    val available = mutableListOf<ModelProvider>()
    if (hasAnthropicKey) available.add(ClaudeProvider())
    if (hasOpenAIKey) available.add(OpenAIProvider())
    if (hasGeminiKey) available.add(GeminiProvider())
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
