# Semantic Comparison Metrics

Semantic comparison measures how closely generated code aligns with the reference
solution **in meaning and structure** — beyond "does it compile and pass tests."

## Overview

The measurement has two layers:

| Layer | Cost | Default | Flag |
|-------|------|---------|------|
| **Structural** (deterministic) | Free — regex/text analysis only | Off | `--semantic` |
| **Semantic Judge** (LLM-as-judge) | Token cost per task | Off | `--semantic-judge` |

Enabling `--semantic-judge` automatically enables the structural layer too.

## Composite Score

Each task+model pair gets a single **compositeSimilarity** score in `[0.0, 1.0]`:

- **Structural only**: weighted average of the 5 structural metrics
- **With judge**: 40% structural + 60% semantic (semantic normalized from 1–5 → 0.0–1.0)

---

## Layer 1: Structural Metrics

All metrics are `Double` values in `[0.0, 1.0]` where `1.0` = identical.

### 1. Token Overlap

**What it measures:** Whether the generated code uses the same identifiers as the reference.

**How:** Extracts all identifiers (variable names, function calls, type names) from both
generated and reference code after stripping comments, string literals, and keywords.
Computes [Jaccard similarity](https://en.wikipedia.org/wiki/Jaccard_index) on the two
identifier sets.

**Signals:** Same naming conventions, same domain concepts referenced.

**Limitations:** Renaming a variable tanks the score even if the logic is identical.

### 2. Import Alignment

**What it measures:** Whether the generated code pulls in the same libraries and APIs.

**How:** Extracts all `import` statements from both codebases, normalizes them
(strips `.*` wildcards), and computes Jaccard similarity.

**Signals:** Same framework usage, same utility choices (e.g., both use `kotlinx.serialization`
rather than `Gson`).

### 3. Public API Match

**What it measures:** Whether the generated code exposes the same public surface —
same function names, class names, and property declarations.

**How:** Regex-extracts declarations (`fun `, `class `, `object `, `val `, `var `,
`interface `, `data class `, `sealed class `) from both codebases. Compares
declaration signatures (name + parameter names) using Jaccard similarity.

**Signals:** Structural compatibility — the generated code could be swapped with the
reference without breaking callers.

### 4. Control Flow Similarity

**What it measures:** Whether the code has a similar complexity shape — same branching
and looping patterns.

**How:** Counts occurrences of control flow constructs (`if`, `when`, `for`, `while`,
`try`, `catch`, `?.let`, `?.run`, `?.also`, `?.apply`) in both codebases, normalizes
to frequency vectors, and computes cosine similarity.

**Signals:** Similar algorithmic approach — e.g., both use a `when` expression rather
than an `if/else` chain; both iterate with `forEach` rather than indexed loops.

### 5. API Version Alignment

**What it measures:** Whether the generated code uses the **same version/generation**
of framework APIs as the reference — not just the same logical API.

**How:** Compares import paths against a known migration mapping table. For each import
in the generated code that maps to a known API, checks whether it uses the same
package path as the reference (indicating the same API version).

**Currently tracked migrations:**

| Framework | Old package (v1) | New package (v2+) |
|-----------|------------------|-------------------|
| Ktor Routing | `io.ktor.routing` | `io.ktor.server.routing` |
| Ktor Application | `io.ktor.application` | `io.ktor.server.application` |
| Ktor Response | `io.ktor.response` | `io.ktor.server.response` |
| Ktor Request | `io.ktor.request` | `io.ktor.server.request` |
| Ktor HTML | `io.ktor.html` | `io.ktor.server.html` |
| Ktor Sessions | `io.ktor.sessions` | `io.ktor.server.sessions` |
| Ktor Auth | `io.ktor.auth` | `io.ktor.server.auth` |
| Ktor WebSockets | `io.ktor.websocket` | `io.ktor.server.websocket` |
| Ktor Content Negotiation | `io.ktor.features.ContentNegotiation` | `io.ktor.server.plugins.contentnegotiation` |
| Ktor Status Pages | `io.ktor.features.StatusPages` | `io.ktor.server.plugins.statuspages` |
| Ktor CORS | `io.ktor.features.CORS` | `io.ktor.server.plugins.cors` |
| kotlinx.serialization Json | `kotlinx.serialization.json.Json` | *(stable — no migration)* |

**Scoring:** fraction of version-sensitive imports that match the reference's API generation.
If the generated code has no version-sensitive imports, the metric defaults to `1.0`
(no evidence of mismatch).

**Relationship to FailureCategory.STALE_API:** This metric quantifies what the failure
categorizer detects as a binary flag. A low API version alignment score on a FAILURE
result strongly correlates with a `STALE_API` categorization.

---

## Layer 2: Semantic Judgment (LLM-as-Judge)

An LLM evaluates the generated code against the reference on a structured rubric.
Requires `--semantic-judge` and uses the model specified by `--judge-model`
(default: `claude-opus-4-6`).

### Dimensions

Each dimension is scored on a 1–5 integer scale.

#### Approach Similarity (1–5)

> Did the model use the same design pattern and architectural approach as the reference?

| Score | Meaning |
|-------|---------|
| 1 | Completely different approach — different algorithm, pattern, or architecture |
| 2 | Different overall approach with some shared elements |
| 3 | Similar high-level approach but significant differences in implementation |
| 4 | Same approach with minor variations in structure or naming |
| 5 | Essentially the same design — differences are cosmetic only |

#### Behavioral Equivalence (1–5)

> Ignoring style differences, does the generated code produce the same behavior as the reference?

| Score | Meaning |
|-------|---------|
| 1 | Fundamentally different behavior — would fail most test cases |
| 2 | Some correct behavior but major gaps or bugs |
| 3 | Core behavior matches but edge cases or error handling differ |
| 4 | Behavior matches in nearly all cases with minor differences |
| 5 | Functionally identical — same outputs for all inputs |

#### Completeness (1–5)

> Does the generated code handle all the cases and requirements that the reference handles?

| Score | Meaning |
|-------|---------|
| 1 | Handles almost none of the reference's cases |
| 2 | Handles some basic cases but misses most requirements |
| 3 | Handles the main cases but misses several secondary ones |
| 4 | Handles nearly all cases with minor omissions |
| 5 | Fully complete — addresses every case the reference does |

### Divergence Explanation

In addition to the three scores, the judge provides a free-text explanation of
**where and why** the generated code diverges from the reference. This appears
in per-task detail sections of the Markdown report.

---

## Reporting

### Summary Table (summary.md)

When semantic comparison is enabled, the Markdown report includes a
**Semantic Similarity** table:

```
| Task           | model-a | model-b |
|----------------|---------|---------|
| crud-endpoints | 0.82    | 0.74    |
| first-tests    | 0.91    | 0.88    |
```

### Comparison Report (comparison.md)

The head-to-head section gains a semantic similarity column. Tasks where models
took **different approaches** (approach similarity < 3) are highlighted even
when both achieved the same Outcome.

### JSON (results.json)

Each `EvalResult` gains an optional `semanticComparison` object:

```json
{
  "structuralMetrics": {
    "tokenOverlap": 0.72,
    "importAlignment": 0.85,
    "publicApiMatch": 0.90,
    "controlFlowSimilarity": 0.78,
    "apiVersionAlignment": 1.0
  },
  "semanticJudgment": {
    "approachSimilarity": 4,
    "behavioralEquivalence": 5,
    "completeness": 4,
    "divergenceExplanation": "Both implementations use Ktor routing DSL...",
    "rawResponse": "..."
  },
  "compositeSimilarity": 0.85
}
```

---

## CLI Flags

| Flag | Description |
|------|-------------|
| `--semantic` | Enable structural metrics (Layer 1) |
| `--semantic-judge` | Enable LLM-as-judge (Layer 2, implies `--semantic`) |
| `--judge-model <id>` | Model for semantic judge (default: `claude-opus-4-6`) |
