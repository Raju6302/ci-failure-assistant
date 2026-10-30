# Log analysis

Before any AI is involved, the worker works out what failed in each job with plain rules:
which step failed, what the error says, which category it falls into, and which files it
points at. This is fast and deterministic, so it runs during ingestion, right after a job's
log is stored. Its result is shown in the dashboard now and becomes the agent's starting
point in Phase 4.

The code is in `analysis-worker`, package `io.github.raju6302.cifa.worker.logs`. The entry
point is `FailureAnalyzer.analyze(JobEvidence)`, which returns a `FailureAnalysis`; it is
stored in the `failure_analysis` table (migration `V3__failure_analysis.sql`).

## Pipeline

```
job log (redacted) + failed step names + check-run annotations
  │
  ├─ LogNormalizer   strip BOM, timestamps, ANSI codes; keep 1-based line numbers; cut lines to 2,000 chars
  ├─ LogSteps        split at "##[group]Run …" / "Post job cleanup."; pick the failing step
  ├─ LogParser       best tool signature first: maven, gradle, node, python, docker; then generic
  ├─ FailureClassifier  timeouts, runner problems, network outages, explicit flaky markers
  ├─ Excerpts        the parser's lines ±3, at most 60 lines / ~6 KB
  ├─ CodeReferences  repo-relative paths from compiler errors, stack traces and annotations
  └─ Fingerprints    SHA-256 of category + key error lines with volatile parts masked
```

**Failing step.** The step with an `##[error]` line, preferring one whose name GitHub lists
as failed, otherwise the last one. Without any error line (a job cut off mid-step), the last
step that ran. The log names a step by its command (`Run mvn -B verify`) while GitHub's job
metadata uses the step's `name:` (`Build and test`); the analysis reports GitHub's name.

**Timeouts.** A job that exceeds `timeout-minutes` gets the conclusion `cancelled`, the same
as a manual or fail-fast cancel. Only its check-run annotation says *"The job has exceeded
the maximum execution time of 1m0s"*. Ingestion therefore fetches annotations for failed and
cancelled jobs, keeps a cancelled job only when that annotation is there, and the analysis
reports it as `INFRASTRUCTURE` with the step's last output, which is usually what it was
waiting for. Fetching annotations needs the App's **Checks: read** permission; without it
(403) jobs are still ingested, but timeouts can't be told apart from cancels.

**Annotations** are stored redacted on `failed_job.annotations`. Failure annotations with a
file (compiler problem matchers, test reporters) add code references, and replace a vague
summary such as "Process completed with exit code 1". When the log has expired, the
analysis is built from annotations alone (`parser = annotations`, no line numbers), or is
`UNKNOWN` with a summary saying there was nothing to go on.

**Line numbers** refer to `failed_job.log_text`, which is the redacted log. Redaction can
join a multi-line private key into one line, so numbers can differ from GitHub's raw log
after such a block.

## Categories

| Category | Typical evidence |
|---|---|
| `COMPILE_ERROR` | javac/kotlinc `File.java:[9,34] cannot find symbol`, `error TS2322`, Python `SyntaxError` |
| `TEST_FAILURE` | Surefire results, Gradle `> test() FAILED`, `✖` / `not ok` / `●`, pytest `FAILED x.py::test` |
| `DEPENDENCY` | Maven/Gradle "Could not find artifact", npm `E404`/`ERESOLVE`, pip "No matching distribution", missing module or base image |
| `FLAKY_TEST` | A test failure **and** an explicit retry marker from the test tool: Surefire `Flakes: 1`, pytest-rerunfailures `RERUN` / "2 rerun", Jest `retryTimes` |
| `INFRASTRUCTURE` | Job timeout, lost runner, shutdown signal, out of memory or disk; network or registry errors underneath a download |
| `CONFIG_ERROR` | File missing from the Docker build context, missing requirements file, `command not found`, missing npm script, action input not supplied |
| `UNKNOWN` | Nothing specific recognized; the summary still names the first error line or the step's last output |

A single log can't prove that a test is flaky, so a failing test without a retry marker is a
`TEST_FAILURE` (the `node-flaky-test` sandbox project included). Detecting flakiness from
history, a test that fails and then passes on the same commit, is part of Phase 7.

## Fingerprints

`Fingerprints.of(category, keyLines)` hashes the category and the lines that identify the
failure (for example each distinct compiler error, or each failed test and its message),
after removing the checkout directory and masking timestamps, temp paths, UUIDs, hex ids,
long random ids and standalone numbers. The same failure on another commit, branch or fork
gets the same fingerprint even if line numbers or values moved; different errors keep
different words and so different fingerprints. Phase 5 uses it to find earlier occurrences.

## Code references

`[{"path": "sandbox-repos/java-compile-error/src/main/java/shop/InvoiceService.java", "line": 9}]`

Absolute paths inside the runner's checkout (`/home/runner/work/<repo>/<repo>/`, and the
macOS, Windows and self-hosted equivalents) become repo-relative; paths outside it
(toolchains, `node_modules`, `site-packages`) are dropped. A path the log prints relative to
the step's working directory (`test_durations.py:9`) is kept as printed, or resolved against
a longer path for the same file seen elsewhere in the log. JVM stack frames name only a class
and file, so their path follows the standard layout (`<module>/src/<main|test>/java/...`).
Phase 5 uses these references to fetch the right code for the agent.

## Untrusted input

Logs come from arbitrary builds. All patterns are precompiled, quantifiers are bounded or
possessive, and every line is cut to 2,000 characters before parsing. Tests feed 2 MB lines
built to cause backtracking to every parser and to the secret redactor (which sees the full,
uncut log). If the analysis still throws, the job is stored without an analysis rather than
blocking the run.

## Adding a parser

1. Create a class implementing `LogParser` in the `logs` package:
   - `name()`: a short id stored in `failure_analysis.parser`.
   - `signature(step)`: 2 when the step clearly ran this tool (its command or an unmistakable
     line), 1 for weaker hints, 0 otherwise. Parsers are tried from the highest score down,
     and the first one that returns a finding wins.
   - `parse(step)`: return a `Finding` with the category, a one-line summary naming the
     actual error, the first and last log line of the relevant output, the key lines for the
     fingerprint (stable words, not timings), and code references built with
     `CodeReferences.of(...)`. Return empty when the tool's output doesn't contain a failure
     you recognize, so the next parser (and finally the generic one) gets a chance.
2. Keep patterns `static final`, anchored where possible, with bounded quantifiers.
3. Register it in `FailureAnalyzer`'s parser list.
4. Add a test with a short synthetic log, and if possible a real log under
   `analysis-worker/src/test/resources/logs` with a case in `SandboxFixtureAnalysisTest`.
