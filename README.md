# CI Failure Assistant

[![CI](https://github.com/Raju6302/ci-failure-assistant/actions/workflows/ci.yml/badge.svg)](https://github.com/Raju6302/ci-failure-assistant/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

An AI agent that explains why a CI build failed and suggests a fix.

When a GitHub Actions run fails, the assistant fetches the logs, finds the code involved
and checks whether the same failure has happened before. It then posts a short diagnosis
on the pull request: what broke, why, the evidence, and a suggested fix.

> **Status:** Phases 0–5 of 11 are done: failed runs are received, their logs fetched (with
> secrets removed) and classified, and a Claude agent diagnoses each failed job using MCP
> tools and retrieved code. Next up: posting diagnoses on pull requests and learning from
> feedback (Phase 6). See the full [roadmap](ROADMAP.md).

## Why

When a pipeline fails, someone opens a 5,000-line log, scrolls to the real error, works
out which change caused it, and decides whether it's a real bug, a flaky test or an
infrastructure problem. That takes 10–30 minutes each time and is mostly repetitive:
matching an error to code and to past fixes. An LLM with the right context can do it.

## How it works

```
 GitHub (workflow_run: failed)
        │ webhook (HMAC verified)
        ▼
┌───────────────────┐  Redis Stream  ┌──────────────────────────┐   MCP   ┌──────────────────┐
│   api-service     │ ─────────────► │    analysis-worker       │ ──────► │ mcp-github-server│──► GitHub API
│ webhooks, REST    │                │ jobs + logs, redaction,  │         │ repo + log tools │
│                   │                │ Claude agent + RAG       │         │                  │
└────────┬──────────┘                └────────────┬─────────────┘         └──────────────────┘
         │                                        │
         ▼                                        ▼
┌───────────────────┐                ┌──────────────────────────┐
│ dashboard (React) │                │ PostgreSQL + pgvector    │
└───────────────────┘                └──────────────────────────┘
```

What works today:

1. **api-service** checks each webhook's `X-Hub-Signature-256`, skips deliveries it has
   already seen, stores failed runs (`failure`, `timed_out`, `startup_failure`), and
   queues them once the database transaction commits. If Redis is down at that moment,
   a relay re-queues the run later. GitHub doesn't redeliver webhooks on its own.
2. **analysis-worker** reads the queue as part of a consumer group, fetches the run's
   failed jobs from the GitHub API (including jobs cancelled by `timeout-minutes`, which
   only their check-run annotations reveal), downloads each log (keeping the last 2 MB,
   where errors usually are), **removes secrets**, and stores it. Temporary GitHub errors
   are retried, permanent ones fail fast, and entries a crashed worker never finished are
   picked up again.
3. **Log analysis** (no AI): the worker finds the failing step, lets a parser for Maven,
   Gradle, Node/Jest/npm, pytest/pip or Docker name the actual error, and classifies it
   (compile error, test failure, dependency, infrastructure, config error…). It keeps the
   relevant log lines with their line numbers, the files involved, and a fingerprint so
   repeats can be grouped. Every sandbox failure gets its expected category except the
   flaky test, which one log can't tell apart from a real test failure (that needs run
   history, Phase 7). See [log analysis](docs/log-analysis.md).
4. **Diagnosis agent:** for each failed job, Claude (Opus 5.5, via the official Anthropic
   SDK) investigates with the MCP tools and submits a structured diagnosis: root cause,
   category, confidence, evidence quoted with file and line, a suggested fix and an
   optional patch. Tokens and cost are recorded. Without an API key, or if the model
   can't be used, each job still gets a rule-based diagnosis. See
   [diagnosis agent](docs/diagnosis-agent.md).
5. **RAG:** at the failing commit, the repository is indexed (only changed files are
   re-embedded, with a local model, so no extra API key) and the agent starts with the most
   relevant code: vector + keyword search, boosted by the files the log points to. On the
   sandbox scenarios, the file at fault is in the top 2 every time. Recurring failures are
   recognized through a failure memory. See [retrieval](docs/rag.md).
6. **dashboard** lists failed runs with their failure category and status, and shows each
   job's error, log excerpt, code references and diagnosis, refreshing every 5 seconds.
7. **sandbox-repos** holds eight small projects that fail on purpose (compile error,
   failing test, missing dependency, flaky test, Docker build, timeout…). The
   *Sandbox failures* workflow runs them on demand, so there are always real failures to test with.
8. **mcp-github-server** offers the agent six read-only MCP tools: commit diffs,
   files with line numbers, recent commits, code search, workflow files and sections of
   the stored, redacted job logs. It only reads repos that sent webhooks, never returns
   the eval answers in `evals/`, caps every result's size, and can require a shared bearer
   token. See [MCP tools](docs/mcp-tools.md).

Authentication works with a **GitHub App** (installation tokens, cached and refreshed)
or a plain **access token** for local development.

## Quick start

Needs Docker. From the repo root:

```bash
cp .env.example .env        # set GITHUB_WEBHOOK_SECRET, GITHUB_TOKEN (or the App settings), ANTHROPIC_API_KEY
docker compose -f infra/docker-compose.yml up --build
```

| Service | URL |
|---|---|
| Dashboard | http://localhost:3000 |
| API + webhook endpoint | http://localhost:8080 (`POST /webhooks/github`, `GET /api/runs`) |
| Worker health | http://localhost:8081/actuator/health |
| MCP server | http://localhost:8082/mcp (Streamable HTTP, see [MCP tools](docs/mcp-tools.md)) |

Send it a test delivery with `scripts/send-test-webhook.sh`, or connect real GitHub
webhooks: see [local development](docs/local-development.md) and
[GitHub App setup](docs/github-app-setup.md).

## Repository layout

```
api-service/        webhooks, REST API, queue producer, relay
analysis-worker/    queue stages: ingestion, redaction, log analysis; diagnosis agent; RAG
mcp-github-server/  MCP tools for the agent: diffs, files, search, workflow files, logs
github-client/      GitHub REST client: App/token auth, jobs, logs, annotations, commits, files, search, webhook signatures
common/             shared status values, queue names and database migrations
dashboard/          React + TypeScript frontend
sandbox-repos/      projects that fail on purpose
evals/              expected diagnoses for the eval suite (Phase 8)
infra/              Docker Compose (Terraform in Phase 9)
docs/               setup guides and design decisions (ADRs)
```

## Tech stack

Java 21 · Spring Boot 4 · Anthropic Java SDK (Claude Opus 5.5) · MCP (Spring AI 2 server, MCP Java SDK
client) · local ONNX embeddings · PostgreSQL 17 + pgvector ·
Redis Streams · React 19 + TypeScript + Vite + Tailwind · JUnit + Testcontainers +
WireMock · Vitest · Docker · GitHub Actions. AWS (ECS, RDS, SQS) arrives in Phase 9.

## Development

```bash
./mvnw verify                          # backend: unit + integration tests (Docker required)
cd dashboard && npm ci && npm test     # frontend
```

Integration tests run against real Postgres and Redis using Testcontainers, and against
a fake GitHub API using WireMock. CI runs the backend and frontend checks, builds every
Docker image and scans for leaked secrets on every push and PR.

## Security

- Webhooks without a valid signature are rejected. With no secret configured, every
  delivery is rejected.
- Logs are scrubbed of tokens, keys, passwords and credentials in URLs **before** they're
  stored. They're never sent to an LLM unredacted.
- No secrets in git: configuration comes from the environment (`.env` is ignored), and
  CI runs gitleaks.
- The REST API has no authentication until Phase 7, so Docker Compose binds every port
  to `127.0.0.1`.

## License

[MIT](LICENSE) © 2026 Vijay Jaripity
