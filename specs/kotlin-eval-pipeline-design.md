# Kotlin AI Code Generation Eval Pipeline — Design Document

## 1. Purpose & Strategic Context

This pipeline measures how well AI models (Claude, GPT, Gemini) generate Kotlin code. The results directly inform the Kotlin DX investment strategy: **"Make Kotlin the best language for AI agents to write code in."**

The pipeline is a **standalone Kotlin/Gradle project** that can evaluate any Kotlin repository that follows the task contract (described below). The first test subject is [nomisRev/ktor-workshop-2025](https://github.com/nomisRev/ktor-workshop-2025).

---

## 2. Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                   Eval Pipeline (this project)          │
│                                                         │
│  ┌───────────┐  ┌───────────┐  ┌──────────────────┐    │
│  │ Task      │  │ Eval      │  │ Report           │    │
│  │ Discovery │→ │ Runner    │→ │ Generator        │    │
│  └───────────┘  └───────────┘  └──────────────────┘    │
│       │              │                   │              │
│       ▼              ▼                   ▼              │
│  reads tasks.json   calls models     writes JSON +     │
│  + TASK.md files    compiles/tests   markdown reports   │
│                                                        │
└────────────────────────┬───────────────────────────────┘
                         │
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
   ┌────────────┐ ┌────────────┐ ┌────────────┐
   │ Target     │ │ Target     │ │ Target     │
   │ Repo A     │ │ Repo B     │ │ Repo C     │
   │ (ktor-ws)  │ │ (future)   │ │ (future)   │
   └────────────┘ └────────────┘ └────────────┘
   Each has tasks.json + TASK.md per branch
```

### Core Loop (per task, per model)

```
1. Clone/checkout target repo to main branch (the "before" state)
2. Read TASK.md from the task branch (the prompt — never show branch code to the model)
3. Gather project context (relevant source files, build config, structure)
4. Send prompt + context to model → receive generated code
5. Apply generated code to the working copy
6. Run Gradle compile → capture compiler output
7. If compile fails AND attempts < maxAttempts:
     Feed compiler errors back to model → go to step 4
8. If compile succeeds: run Gradle tests → capture test output
9. Score the result
10. Categorize any failures
11. Log everything (prompt, response, errors, scores, timing)
```

---

## 3. Target Repository Contract

Any Kotlin repository can be evaluated if it provides:

### 3.1 `tasks.json` (in repository root)

```json
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
        "backend/src/main/resources/**",
        "backend/build.gradle.kts",
        "build.gradle.kts",
        "settings.gradle.kts",
        "gradle/libs.versions.toml"
      ]
    }
  ]
}
```

### 3.2 `TASK.md` (on each task branch)

A natural-language prompt file that lives on the task branch. This is what the model sees. Example:

```markdown
# Task: Write First Tests

Write tests for the existing Ktor application. The tests should verify:
- The root endpoint `/` returns a 200 status code
- The response contains expected content

Use Ktor's `testApplication` API and kotlin-test assertions.
Place tests in `backend/src/test/kotlin/`.
```

### 3.3 Reference Solution

The branch code itself IS the reference solution. It is used for:
- Comparing the model's output (diff similarity)
- Verifying that tests actually pass (the branch should be green)
- Never shown to the model during evaluation

---

## 4. Pipeline Project Structure

```
kotlin-eval-pipeline/
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml
│
├── src/main/kotlin/eval/
│   ├── Main.kt                          # CLI entry point
│   │
│   ├── model/                           # Data model
│   │   ├── TaskManifest.kt              # tasks.json schema
│   │   ├── EvalResult.kt                # Per-task-per-model result
│   │   ├── EvalRun.kt                   # Full run metadata
│   │   └── FailureCategory.kt           # Failure taxonomy
│   │
│   ├── runner/                          # Core eval logic
│   │   ├── EvalRunner.kt               # Orchestrates the full eval loop
│   │   ├── TaskExecutor.kt             # Executes a single task against a single model
│   │   ├── ProjectSandbox.kt           # Git checkout + file management in isolation
│   │   └── GradleExecutor.kt           # Runs Gradle compile/test, parses output
│   │
│   ├── provider/                        # Model integrations
│   │   ├── ModelProvider.kt             # Interface
│   │   ├── ClaudeProvider.kt            # Anthropic Messages API
│   │   ├── OpenAIProvider.kt            # OpenAI Chat Completions API
│   │   ├── GeminiProvider.kt            # Google Gemini API
│   │   └── ResponseParser.kt           # Extracts file contents from model responses
│   │
│   ├── context/                         # Project context gathering
│   │   ├── ContextBuilder.kt           # Builds prompt context from project files
│   │   └── PromptAssembler.kt          # Combines TASK.md + context into final prompt
│   │
│   ├── scoring/                         # Result evaluation
│   │   ├── Scorer.kt                    # Computes metrics from attempts
│   │   ├── FailureCategorizer.kt       # Classifies failures by root cause
│   │   └── IdiomaticJudge.kt           # LLM-as-judge for code quality (optional)
│   │
│   └── reporting/                       # Output
│       ├── JsonReporter.kt             # Raw JSON results
│       ├── MarkdownReporter.kt         # Human-readable summary
│       └── ComparisonReport.kt         # Cross-model comparison tables
│
├── src/test/kotlin/eval/               # Pipeline self-tests
│   ├── model/TaskManifestTest.kt
│   ├── runner/GradleExecutorTest.kt
│   ├── provider/ResponseParserTest.kt
│   └── context/PromptAssemblerTest.kt
│
└── results/                             # Output directory (gitignored)
    └── {timestamp}-{project}/
        ├── run-metadata.json
        ├── results.json
        ├── summary.md
        └── attempts/
            └── {task-id}/{model}/{attempt-N}/
                ├── prompt.txt
                ├── response.txt
                ├── generated-files/
                ├── compiler-output.txt
                └── test-output.txt
```

---

## 5. Component Specifications

### 5.1 Data Model

#### TaskManifest.kt

Deserialized from `tasks.json`. Fields:

| Field | Type | Description |
|-------|------|-------------|
| `projectName` | `String` | Human-readable project name |
| `repository` | `String` | Git clone URL |
| `mainBranch` | `String` | Branch used as "before" state (default: `"main"`) |
| `tasks` | `List<TaskDefinition>` | All tasks to evaluate |

#### TaskDefinition

| Field | Type | Description |
|-------|------|-------------|
| `id` | `String` | Unique task identifier |
| `branch` | `String` | Git branch with TASK.md + reference solution |
| `title` | `String` | Human-readable title |
| `difficulty` | `Difficulty` | `TRIVIAL`, `EASY`, `MEDIUM`, `HARD`, `EXPERT` |
| `tags` | `List<String>` | Categorization tags (e.g., `"routing"`, `"coroutines"`) |
| `verification` | `Verification` | How to compile and test |
| `maxAttempts` | `Int` | Max retry iterations (default: 3) |
| `contextFiles` | `List<String>` | Glob patterns for files to include in prompt context |

#### Verification

| Field | Type | Description |
|-------|------|-------------|
| `compileTask` | `String` | Gradle task for compilation (e.g., `:backend:compileKotlin`) |
| `testTask` | `String` | Gradle task for tests (e.g., `:backend:test`) |
| `requiredTests` | `List<String>` | Specific test classes that must pass (empty = all) |

#### EvalResult.kt

One per task-model combination:

| Field | Type | Description |
|-------|------|-------------|
| `taskId` | `String` | Task identifier |
| `model` | `String` | Model name (e.g., `"claude-sonnet-4-20250514"`) |
| `timestamp` | `Instant` | When this eval ran |
| `attempts` | `List<Attempt>` | Each generate-compile-test cycle |
| `finalOutcome` | `Outcome` | `SUCCESS`, `PARTIAL` (compiles, tests fail), `FAILURE` |
| `metrics` | `EvalMetrics` | Computed metrics |
| `failureCategory` | `FailureCategory?` | Root cause if not SUCCESS |

#### Attempt

| Field | Type | Description |
|-------|------|-------------|
| `attemptNumber` | `Int` | 1-indexed |
| `generatedCode` | `Map<String, String>` | filepath → content |
| `compileSuccess` | `Boolean` | Did compilation pass? |
| `compilerErrors` | `List<String>` | Raw compiler error output |
| `testSuccess` | `Boolean` | Did all tests pass? |
| `testResults` | `TestResults?` | Parsed test results |
| `durationMs` | `Long` | Time for this attempt (model call + compile + test) |

#### EvalMetrics

| Field | Type | Description |
|-------|------|-------------|
| `firstTryCompile` | `Boolean` | Compiled on first attempt |
| `firstTryTestPass` | `Boolean` | Tests passed on first attempt |
| `attemptsToSuccess` | `Int?` | Null if never succeeded |
| `totalDurationMs` | `Long` | Total time across all attempts |
| `recoveredFromError` | `Boolean` | Failed first, succeeded later |

#### FailureCategory

| Value | Description | Maps to DX Investment |
|-------|-------------|----------------------|
| `WRONG_API_USAGE` | Hallucinated or incorrect API calls | Spec-level documentation |
| `COMPILES_WRONG_BEHAVIOR` | Compiles but tests fail | Fast feedback loops |
| `NO_COMPILE_NO_RECOVERY` | Doesn't compile, couldn't self-fix | Structured compiler errors |
| `NON_IDIOMATIC` | Works but uses Java patterns | Training data / docs quality |
| `FORMATTING_MISMATCH` | Style/formatting issues | Deterministic formatting |
| `STALE_API` | Used deprecated or outdated APIs | Spec-level documentation |

### 5.2 ProjectSandbox

Manages isolated working copies for each eval run.

**Responsibilities:**
- Clone the target repository (or use a local path)
- Checkout `main` branch as the "before" state
- Read `TASK.md` from a task branch without checking it out: `git show {branch}:TASK.md`
- Apply generated code files to the working copy
- Reset to clean state between attempts (revert to main, re-apply)
- Reset to clean state between tasks

**Key design choice:** Use a single clone with `git checkout` and `git clean` for resets, not fresh clones per task. Faster and preserves Gradle daemon/caches.

**Working directory isolation:** Each eval run gets its own working directory under a temp folder. Multiple models can run in parallel with separate sandboxes.

### 5.3 GradleExecutor

Runs Gradle tasks and parses output.

**Responsibilities:**
- Execute `./gradlew {compileTask}` and capture stdout + stderr
- Execute `./gradlew {testTask}` and capture stdout + stderr
- Parse compiler errors from output (line-by-line parsing of `e:` prefixed lines)
- Parse test results from Gradle XML reports (`build/test-results/`)
- Enforce a timeout (configurable, default 5 minutes per Gradle invocation)
- Reuse Gradle daemon across attempts within the same sandbox

**Compiler error extraction:** Parse lines matching the pattern:
```
e: file:///path/to/File.kt:42:15 Error message here
```
Extract file path, line, column, and message.

**Test result parsing:** Read JUnit XML from `build/test-results/{testTask}/` directory. Extract test count, pass/fail counts, and failure messages.

### 5.4 ModelProvider Interface

```
Interface: ModelProvider
  - name: String (model identifier)
  - generateCode(prompt, projectContext, previousErrors): GeneratedCode
```

**ProjectContext** contains:
- `files: Map<String, String>` — filepath → content for relevant source files
- `buildConfig: String` — build.gradle.kts content
- `projectStructure: String` — tree-like listing of the project

**GeneratedCode** contains:
- `files: Map<String, String>` — filepath → generated content
- `explanation: String?` — model's explanation (if any)
- `rawResponse: String` — full model response for logging

#### Provider Implementations

All three providers (Claude, OpenAI, Gemini) follow the same pattern:

1. Read API key from environment variable (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, `GEMINI_API_KEY`)
2. Construct a system prompt that instructs the model to:
   - Respond with file contents in a parseable format
   - Only include files that need to be created or modified
   - Use the exact file paths relative to the project root
3. Send the prompt with project context
4. Parse the response to extract file contents

**System prompt (shared across providers):**
```
You are a Kotlin developer working on a Ktor project.
You will be given a task description and project context.
Respond ONLY with the file contents that need to be created or modified.

Use this exact format for each file:

FILE: path/to/File.kt
```kotlin
// your code here
```

FILE: path/to/AnotherFile.kt
```kotlin
// your code here
```

Rules:
- Only include files you are creating or modifying
- Use exact file paths relative to the project root
- Do not include explanations outside of file blocks
- Write idiomatic Kotlin, not Java-style Kotlin
- Use the project's existing patterns and dependencies
```

**Error feedback prompt (for retry attempts):**
```
The previous code did not compile. Here are the compiler errors:

{compiler errors}

Please fix the code. Respond with the complete corrected files using the same format.
```

**HTTP clients:** Use Ktor Client (CIO engine) with Content Negotiation plugin for kotlinx.serialization. Each provider constructs the appropriate request body for its API:
- Claude: `POST https://api.anthropic.com/v1/messages` with `x-api-key` header
- OpenAI: `POST https://api.openai.com/v1/chat/completions` with `Authorization: Bearer` header
- Gemini: `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent` with API key parameter

**Configurable models (defaults):**
- Claude: `claude-sonnet-4-20250514`
- OpenAI: `gpt-4o`
- Gemini: `gemini-2.0-flash`

### 5.5 ResponseParser

Parses model responses to extract files. This is shared across all providers.

**Parsing logic:**
1. Split response on `FILE:` markers
2. For each section, extract the file path (first line after `FILE:`)
3. Extract code from the fenced code block (between `` ```kotlin `` or `` ``` `` and closing `` ``` ``)
4. Return `Map<String, String>` of filepath → content

**Edge cases to handle:**
- Model includes explanation text before/after file blocks → ignore non-file content
- Model uses slightly different markers (e.g., `## File:`, `// File:`) → normalize
- Code block language tag varies (`kotlin`, `kt`, `kts`, none) → accept all
- Model wraps entire response in a code block → detect and unwrap
- Model returns a single file without `FILE:` marker → infer path from task context

### 5.6 ContextBuilder

Builds the project context to send to the model.

**Logic:**
1. Read `contextFiles` glob patterns from the task definition
2. Resolve globs against the project working directory
3. Read each matching file's content
4. Build a `projectStructure` string by running `find` on the project (or walking the file tree), showing relevant directories and files
5. Read the build configuration files

**Context size management:**
- Set a configurable token budget (default: 30,000 tokens estimated as chars/4)
- Prioritize: TASK.md → build files → source files most relevant to the task → other source files
- If context exceeds budget, truncate least-relevant files with a note: `[file truncated — {N} lines omitted]`

### 5.7 PromptAssembler

Combines TASK.md + context into the final prompt sent to the model.

**First attempt prompt structure:**
```
## Task

{contents of TASK.md}

## Project Structure

{tree output of relevant directories}

## Build Configuration

### build.gradle.kts
{content}

### gradle/libs.versions.toml
{content}

## Source Files

### {filepath}
```kotlin
{content}
```

### {filepath}
```kotlin
{content}
```
```

**Retry prompt structure (attempts 2+):**
```
## Task

{contents of TASK.md}

## Previous Attempt Failed

The code you generated did not compile. Here are the errors:

{compiler errors — full Gradle output}

## Your Previous Code

{the files you generated last attempt}

## Project Context

{same context as before, abbreviated if needed to fit token budget with errors}

Please fix the issues and provide the corrected files.
```

### 5.8 EvalRunner

The top-level orchestrator.

**Input:** Path to target repository (local clone), optional filters (specific tasks, specific models).

**Flow:**
1. Read `tasks.json` from the target repo root
2. Validate: all referenced branches exist, TASK.md files exist on branches
3. For each task × model combination:
   a. Create a sandbox (or reuse with clean reset)
   b. Execute via `TaskExecutor`
   c. Collect `EvalResult`
4. Aggregate into `EvalRun`
5. Generate reports

**Parallelism:** Run different models in parallel (separate sandboxes). Tasks within a model run sequentially (shared sandbox with resets).

**CLI interface (Main.kt):**
```
Usage: kotlin-eval-pipeline [options]
  --repo <path>           Path to local clone of target repository (required)
  --tasks <id,id,...>     Run only specific tasks (default: all)
  --models <name,...>     Run only specific models (default: all configured)
  --max-attempts <n>      Override max attempts (default: from tasks.json)
  --output <dir>          Output directory for results (default: ./results)
  --parallel              Run models in parallel (default: sequential)
  --dry-run               Validate setup without calling models
```

### 5.9 Scorer

Computes `EvalMetrics` from the list of `Attempt`s.

**Logic is straightforward:**
- `firstTryCompile` = `attempts[0].compileSuccess`
- `firstTryTestPass` = `attempts[0].compileSuccess && attempts[0].testSuccess`
- `attemptsToSuccess` = index + 1 of first attempt where `testSuccess == true`, or null
- `totalDurationMs` = sum of all attempt durations
- `recoveredFromError` = first attempt failed but a later attempt succeeded

### 5.10 FailureCategorizer

Classifies failures by examining compiler errors, test failures, and the generated code.

**Heuristic-based classification (v1 — rule-based):**

| Check | Category |
|-------|----------|
| Compiler error contains "unresolved reference" for an API that doesn't exist | `WRONG_API_USAGE` |
| Compiler error references a deprecated API | `STALE_API` |
| Code compiles but tests fail | `COMPILES_WRONG_BEHAVIOR` |
| Code doesn't compile after all retry attempts | `NO_COMPILE_NO_RECOVERY` |
| Code works but uses Java patterns (detected by keyword heuristics) | `NON_IDIOMATIC` |
| Formatting doesn't match project style | `FORMATTING_MISMATCH` |

**v2 (future):** Use LLM-as-judge to classify failures more accurately. Feed the compiler errors + generated code + reference solution to a model and ask it to categorize.

### 5.11 Reporting

#### JsonReporter
Writes the full `EvalRun` as a JSON file. This is the raw data for further analysis.

#### MarkdownReporter
Generates a human-readable summary:

```markdown
# Eval Results: ktor-workshop-2025
Run: 2025-07-15T10:30:00Z

## Summary Table

| Task | Difficulty | Claude | GPT-4o | Gemini |
|------|-----------|--------|--------|--------|
| first-tests | EASY | ✅ (1) | ✅ (2) | ❌ |
| crud-impl | MEDIUM | ✅ (1) | ✅ (1) | ✅ (3) |
| add-auth | HARD | ❌ | ❌ | ❌ |

Legend: ✅ (N) = succeeded in N attempts, ❌ = failed all attempts

## Metrics

| Metric | Claude | GPT-4o | Gemini |
|--------|--------|--------|--------|
| First-try compile rate | 75% | 60% | 50% |
| First-try test pass rate | 50% | 40% | 25% |
| Recovery rate | 80% | 60% | 40% |
| Avg attempts to success | 1.5 | 2.1 | 2.8 |
| Avg time to solution | 45s | 52s | 38s |

## Failure Distribution

| Category | Count | % |
|----------|-------|---|
| Wrong API usage | 5 | 33% |
| Can't self-fix from errors | 4 | 27% |
| Compiles, wrong behavior | 3 | 20% |
| Stale API knowledge | 2 | 13% |
| Non-idiomatic | 1 | 7% |

## Per-Task Details
...
```

#### ComparisonReport
Cross-model comparison focusing on where models diverge — same task, different outcomes. Highlights which compiler errors were recoverable by which models.

---

## 6. Task Design for ktor-workshop-2025

Based on the repo's branch structure, here are the tasks to create. Each needs a `TASK.md` on its branch.

| Task ID | Branch | Title | Difficulty | Tags |
|---------|--------|-------|-----------|------|
| `first-tests-empty` | `branch01` | Set up test infrastructure | TRIVIAL | `testing`, `setup` |
| `first-tests-impl` | `branch02` | Write first endpoint tests | EASY | `testing`, `ktor-test` |
| `crud-endpoints` | `branch03` | Implement CRUD routes | MEDIUM | `routing`, `serialization`, `crud` |
| `structure-di` | `branch04` | Add project structure and DI | MEDIUM | `architecture`, `di` |
| `exposed-basics` | `branch05` | Database access with Exposed | MEDIUM | `database`, `exposed` |
| `exposed-relations` | `branch06` | Add database relations | HARD | `database`, `exposed`, `relations` |
| `exposed-entities` | `branch07` | Add Exposed DAO entities | HARD | `database`, `exposed`, `dao` |
| `auth-oauth-jwt` | `branch09` | Authentication with OAuth2, sessions, JWT | EXPERT | `auth`, `oauth`, `jwt`, `sessions` |
| `websocket-sse` | `branch10` | WebSocket & SSE with serialization | HARD | `websocket`, `sse`, `serialization` |

Note: branch08 (TestContainers integration testing) is in parentheses in the README, suggesting it may be optional/incomplete — skip or mark as optional.

---

## 7. Dependencies

**Principle:** Default to stable JetBrains libraries. Only use third-party if no JetBrains equivalent exists.

| Library | Source | Purpose |
|---------|--------|---------|
| kotlinx-serialization-json | JetBrains | JSON serialization for all data models |
| kotlinx-coroutines-core | JetBrains | Async execution, parallel model runs |
| ktor-client-core + ktor-client-cio | JetBrains | HTTP calls to model APIs |
| ktor-client-content-negotiation + ktor-serialization-kotlinx-json | JetBrains | JSON request/response bodies for model APIs |
| kotlin-test + kotlin-test-junit5 | JetBrains | Testing framework |
| slf4j-api + slf4j-simple | Third-party (no JetBrains alternative) | Logging |

```toml
# gradle/libs.versions.toml

[versions]
kotlin = "2.1.10"
kotlinx-serialization = "1.7.3"
kotlinx-coroutines = "1.9.0"
ktor = "3.1.2"
slf4j = "2.0.16"

[libraries]
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "kotlinx-serialization" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "kotlinx-coroutines" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test" }
kotlin-test-junit5 = { module = "org.jetbrains.kotlin:kotlin-test-junit5" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

---

## 8. Configuration

The pipeline reads configuration from environment variables and an optional `eval-config.yaml`:

```yaml
# eval-config.yaml (optional, overrides defaults)

models:
  claude:
    apiKeyEnv: ANTHROPIC_API_KEY
    model: claude-sonnet-4-20250514
    enabled: true
  openai:
    apiKeyEnv: OPENAI_API_KEY
    model: gpt-4o
    enabled: true
  gemini:
    apiKeyEnv: GEMINI_API_KEY
    model: gemini-2.0-flash
    enabled: true

defaults:
  maxAttempts: 3
  gradleTimeoutSeconds: 300
  contextTokenBudget: 30000
  parallel: false

output:
  directory: ./results
  keepAttemptArtifacts: true
```

---

## 9. Implementation Order

Build and test in this order:

### Phase 1: Foundation
1. **Data model** (`model/` package) — all data classes with serialization, round-trip tests
2. **ResponseParser** — parse model output format, test with sample responses
3. **GradleExecutor** — run Gradle, parse errors and test results, test against the ktor-workshop repo

### Phase 2: Core Loop
4. **ProjectSandbox** — git operations, file management, clean reset
5. **ContextBuilder** — gather files from glob patterns, build context string
6. **PromptAssembler** — combine TASK.md + context into prompt
7. **TaskExecutor** — single task × single model execution with retry loop

### Phase 3: Model Integration
8. **ModelProvider implementations** — Claude, OpenAI, Gemini HTTP calls
9. **EvalRunner** — orchestrate all tasks × all models
10. **CLI (Main.kt)** — command-line argument parsing

### Phase 4: Scoring & Reporting
11. **Scorer** — compute metrics from attempts
12. **FailureCategorizer** — heuristic-based failure classification
13. **JsonReporter + MarkdownReporter** — output generation

### Phase 5: Polish
14. **ComparisonReport** — cross-model analysis
15. **IdiomaticJudge** — LLM-as-judge for code quality (optional)
16. **Create TASK.md files** for all ktor-workshop branches

---

## 10. Testing Strategy

### Unit Tests
- `TaskManifestTest` — serialize/deserialize round-trip
- `ResponseParserTest` — various model output formats, edge cases
- `GradleExecutorTest` — parse real compiler error output and test XML
- `PromptAssemblerTest` — verify prompt structure
- `ScorerTest` — metrics computation from attempt data
- `FailureCategorizerTest` — classification rules

### Integration Tests
- `ProjectSandboxTest` — clone ktor-workshop, checkout branches, apply files, reset
- `GradleExecutorIntegrationTest` — actually compile the ktor-workshop project
- `TaskExecutorTest` — run a single task end-to-end with a mock provider

### End-to-End
- Run the full pipeline against ktor-workshop with one easy task and one model
- Verify all output artifacts are generated correctly

---

## 11. TASK.md Authoring Guidelines

When writing TASK.md files for the ktor-workshop branches:

1. **Be specific about what to create** — name the files, packages, and classes expected
2. **Don't give away the implementation** — describe WHAT, not HOW
3. **Reference existing code** — "Look at the existing route structure in `Routes.kt`" helps the model find patterns
4. **Specify the expected behavior** — what should the tests verify, what endpoints should exist
5. **Include constraints** — "Use Ktor's Content Negotiation plugin" rather than letting the model pick
6. **Don't reference the branch or solution** — the model shouldn't know there's a reference implementation

---

## 12. Future Extensions

- **Structured compiler error experiment:** Run the same tasks with raw `kotlinc` errors vs enriched structured errors. Measure the delta in recovery rate. This directly validates the #1 DX investment priority.
- **LLM-as-judge scoring:** Use a model to rate generated code on idiomatic-ness, comparing against the reference solution.
- **CI integration:** Run evals automatically when model versions change or Kotlin/Ktor updates ship.
- **Additional repositories:** Add more Kotlin projects (Compose Multiplatform, KMP library, Gradle plugin) to broaden the eval surface.
- **Prompt variation testing:** Same task, different prompt styles — measure which prompting strategies work best for Kotlin.
