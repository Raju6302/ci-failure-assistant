# CI Failure Assistant: Roadmap

An AI agent that explains why a CI build failed and suggests a fix.

When a GitHub Actions run fails, the assistant fetches the logs, finds the code related to the error, and checks whether the same failure has happened before. It then posts a short diagnosis to the pull request: what broke, why, the evidence, and a suggested fix.

> **Progress:** Phase 0 ✅ · Phase 1 ✅ · next: Phase 2 (log processing and classification)

---

## 1. The problem

When a pipeline fails, developers open a log of 5,000+ lines, scroll to find the real error, work out which change caused it, and check whether it's a real bug, a flaky test or an infrastructure problem. This happens many times a week and usually takes 10–30 minutes each time. Most of that work is repetitive: matching an error to code and to past fixes. An LLM with the right context can do it.

## 2. What v1.0 does

1. Receives a GitHub webhook when a workflow run fails.
2. Downloads the job logs, removes secrets, and extracts the lines with the actual error.
3. Sorts the failure into a category: compile error, test failure, dependency issue, flaky test, infrastructure/timeout, or config error.
4. Runs an AI agent that:
   - uses **MCP tools** to read the commit diff, the files involved and the workflow file
   - uses **RAG** to pull related code chunks and similar past failures
   - returns a structured diagnosis: root cause, confidence, evidence lines, suggested fix
5. Posts the diagnosis as a PR comment or check-run summary, and optionally opens a draft PR with a patch.
6. Shows everything in a dashboard: failure history, trends, flaky tests and time to fix.
7. Learns from feedback: confirmed fixes are saved to "failure memory" and reused next time.

**Not in v1:** GitLab, Jenkins and CircleCI support; merging fixes automatically; billing and multi-tenant SaaS.

---

## 3. Architecture

```
 GitHub (workflow_run: failed)
        │ webhook (HMAC verified)
        ▼
┌───────────────────┐  job queue   ┌──────────────────────────┐   MCP   ┌──────────────────┐
│   api-service     │ ───────────► │    analysis-worker       │ ──────► │ mcp-github-server│──► GitHub API
│ Spring Boot       │              │ Spring AI agent          │         │ tools: diff, file│
│ webhooks, REST,   │              │ log parsing, RAG,        │         │ commits, search, │
│ auth, settings    │              │ diagnosis, PR comments   │         │ workflow, logs   │
└────────┬──────────┘              └────────────┬─────────────┘         └──────────────────┘
         │                                      │
         ▼                                      ▼
┌───────────────────┐              ┌──────────────────────────┐
│ dashboard (React) │              │ PostgreSQL + pgvector    │
│ failures, trends, │              │ runs, failures, diagnoses│
│ feedback          │              │ code chunks, memories    │
└───────────────────┘              └──────────────────────────┘
```

**Design choice:** the project starts with **4 separately deployable parts** that run locally with Docker Compose, connected by a queue. They become fully separate microservices on AWS in Phase 9. As a solo project, this keeps the product moving early, and the boundaries are set from the start, so splitting later is cheap.

## 4. Tech stack

| Area | Choice |
|---|---|
| Backend | Java 21, Spring Boot 4, Maven multi-module |
| AI | Spring AI 2 (ChatClient, tool calling, structured output); provider can be swapped, Claude by default |
| Tools | MCP server built with Spring AI's MCP server starter; the worker connects as an MCP client |
| RAG | PostgreSQL + pgvector, hybrid search (keyword + vector), Spring AI `VectorStore` |
| Queue | Redis Streams locally → AWS SQS in production |
| Frontend | React, TypeScript, Vite, Tailwind, TanStack Query, Recharts |
| Database | PostgreSQL 16, Flyway migrations |
| Testing | JUnit, Testcontainers, WireMock (fake GitHub API), Vitest + Testing Library; LLM eval suite |
| DevOps | Docker Compose, GitHub Actions, Terraform, AWS ECS Fargate, RDS, Secrets Manager |
| Observability | OpenTelemetry, structured logs, LLM token and cost tracking |

## 5. Repository layout

```
ci-failure-assistant/
├── api-service/          # webhooks, REST API, auth, settings
├── analysis-worker/      # log processing, agent, RAG, GitHub output
├── mcp-github-server/    # MCP tools wrapping the GitHub API
├── github-client/        # GitHub REST client: App/token auth, jobs, logs, webhook signatures
├── common/               # shared status values, queue names, database migrations
├── dashboard/            # React + TypeScript frontend
├── evals/                # failure dataset + scoring harness
├── sandbox-repos/        # small sample projects with intentionally broken builds
├── infra/
│   ├── docker-compose.yml
│   └── terraform/
├── scripts/              # developer helpers (e.g. send a signed test webhook)
├── docs/                 # architecture decisions (ADRs), setup guides
├── LICENSE               # MIT
└── README.md
```

## 6. Data model (first version)

- `repository`: installed repo, settings, GitHub App installation id
- `workflow_run`: run id, commit SHA, branch, PR number, status, timestamps
- `failure`: job, step, category, extracted error lines, fingerprint
- `diagnosis`: root cause, confidence, evidence, suggested fix, model used, tokens, cost, latency
- `feedback`: 👍/👎, "this was the actual fix" link
- `code_chunk`: repo, path, symbol, content, embedding (`vector`)
- `failure_memory`: fingerprint, summary, resolution, embedding (`vector`)

---

## 7. Roadmap

Total estimate: **about 136 hours** across 11 phases. Each phase ends with something working and demoable, plus tests.

### Phase 0: Foundations (8h)
- [x] Repo, MIT license, README, `.env.example` (no secrets committed, ever)
- [x] Maven multi-module skeleton: `api-service`, `analysis-worker`, `mcp-github-server`, `common`
- [x] React + TypeScript + Vite + Tailwind skeleton for `dashboard`
- [x] Docker Compose: Postgres + pgvector, Redis, all services
- [x] Flyway baseline migration, health endpoints
- [x] GitHub Actions: build, tests, lint, Docker image builds and secret scanning on every push and PR

**Done when:** `docker compose up` starts everything and CI passes.

### Phase 1: Failure ingestion (12h)
- [x] GitHub auth: App installation tokens (signed JWT, cached and refreshed), or an access token for local development
- [x] [GitHub App setup guide](docs/github-app-setup.md) with minimum permissions (registering the App itself is done on github.com)
- [x] Webhook endpoint for `workflow_run.completed`, with HMAC signature verification and duplicate-delivery protection
- [x] Store failed runs; queue them on a Redis Stream after commit, with a relay for runs that never reached the queue ([ADR 3](docs/adr/0003-redis-streams-queue-with-relay.md))
- [x] Worker: fetch each run's failed jobs and download their logs (last 2 MB kept), with retries and crash recovery
- [x] Basic secret redaction before logs are stored (moved up from Phase 2: logs shouldn't be stored unredacted even briefly)
- [x] Sandbox projects with intentionally broken builds (Maven, npm, pytest, Docker, flaky test, timeout) and a workflow that runs them on demand
- [x] Dashboard: failed runs and their failed jobs, refreshed every 5 seconds
- [x] Integration tests with Testcontainers (Postgres, Redis) and WireMock (GitHub API)

**Done when:** a failed run in a sandbox repo appears in the database within seconds.

### Phase 2: Log processing and classification (12h)
- [ ] Extend **secret redaction** (basic version shipped in Phase 1): more token formats, a test corpus, redaction counts per log
- [ ] Error extraction: find the failing step and trim to the relevant lines with context
- [ ] Parsers for common formats: Maven/Gradle, JUnit reports, npm/Jest, pytest, Docker build
- [ ] Rule-based classification into categories, plus a failure fingerprint so repeats can be grouped
- [ ] Show category and error excerpt in the dashboard

**Done when:** failures are classified correctly, without any AI, on the sandbox set. (**Milestone M1**)

### Phase 3: MCP GitHub server (14h)
- [ ] MCP server with tools:
  - `get_commit_diff(repo, sha)`
  - `get_file(repo, path, ref, line_range)`
  - `list_recent_commits(repo, branch, n)`
  - `search_code(repo, query)`
  - `get_workflow_file(repo, run_id)`
  - `get_job_log_section(run_id, job, step)`
- [ ] Auth with the GitHub App installation token, rate-limit handling, response caching
- [ ] Output size limits so a tool never floods the model's context
- [ ] Contract tests for every tool, and a guide to testing it with any MCP client

**Done when:** the MCP server can be connected to any MCP client and its tools return correct data.

### Phase 4: AI diagnosis agent (18h)
- [ ] Spring AI ChatClient connected to the MCP tools as an MCP client
- [ ] System prompt and agent loop with a step limit and token budget
- [ ] Structured output: `rootCause`, `category`, `confidence`, `evidence[]` (file and line references), `suggestedFix`, `patch?`
- [ ] Retries, timeouts, and a fallback to a rule-based summary if the LLM fails
- [ ] Track tokens, cost and latency per diagnosis
- [ ] Diagnosis detail page in the dashboard

**Done when:** a sandbox failure produces a correct, evidence-backed diagnosis. (**Milestone M2: MVP**)

### Phase 5: RAG, with code context and failure memory (16h)
- [ ] Code indexer: split code by file, class and method; create embeddings; store in pgvector
- [ ] Incremental re-index on `push` webhooks (only changed files)
- [ ] Hybrid retrieval (Postgres full-text + vector) with re-ranking
- [ ] Failure memory: embed past failures with their confirmed fixes; retrieve similar ones
- [ ] Add retrieved context to the agent ("this looks like failure #42, which was fixed by …")
- [ ] Compare diagnosis quality with RAG versus without it, using the eval set

**Done when:** repeat failures are recognized and diagnoses cite the right files more often.

### Phase 6: GitHub output and feedback loop (12h)
- [ ] Post the diagnosis as a PR comment and a check-run summary, updating the same comment instead of posting new ones
- [ ] Optional: open a **draft PR** with the suggested patch (setting per repo, off by default)
- [ ] 👍/👎 feedback from the dashboard and the PR comment
- [ ] When the next run on that branch passes, link the fix commit to the failure and save it to failure memory

**Done when:** a developer sees the diagnosis on their PR, and confirmed fixes improve future answers. (**Milestone M3**)

### Phase 7: Dashboard (12h)
- [ ] Failures list with filters (repo, branch, category, date)
- [ ] Detail view: highlighted log lines, diagnosis, evidence links, feedback
- [ ] Insights: top failing tests, flaky test detection (fail→pass on the same commit), time to fix, failure rate by category
- [ ] Repo settings page: on/off, draft PRs, ignored workflows
- [ ] GitHub OAuth login; users see only repos they can access

**Done when:** a team lead can answer "what's breaking our builds this week?" in one screen.

### Phase 8: Evals and test coverage (10h)
- [ ] Eval dataset: 50+ real failures from the sandbox repos and public OSS projects, each with the known root cause
- [ ] Scoring: category accuracy, whether the root cause matches (LLM-as-judge plus checks against the known answer), evidence precision
- [ ] Run the evals in CI on prompt or model changes and fail the build if quality drops
- [ ] Bring unit and integration test coverage up to a solid level on the core modules

**Done when:** every prompt change shows a before/after quality score.

### Phase 9: Separate services and AWS deployment (16h)
- [ ] Replace Redis Streams with **AWS SQS** behind the same queue interface
- [ ] Deploy each service separately to **ECS Fargate**, with **RDS Postgres** (pgvector) and **Secrets Manager**
- [ ] Terraform for all infrastructure; GitHub Actions deploys on merge to `main`
- [ ] OpenTelemetry tracing across api → queue → worker → MCP server
- [ ] Autoscaling the worker on queue depth

**Done when:** the system runs in AWS and handles a burst of failures without falling behind. (**Milestone M4**)

### Phase 10: Hardening and v1.0 release (6h)
- [ ] Security review: webhook verification, minimum permissions, redaction tests, dependency scanning
- [ ] Rate limits and per-repo cost caps for LLM usage
- [ ] Docs: setup guide, architecture decisions, how to add a new log parser
- [ ] Demo video + tagged **v1.0** release

---

## 8. Milestones

| Milestone | After phase | What it shows |
|---|---|---|
| **M1: Classifier** | 2 | Failures captured, cleaned of secrets and classified, with no AI |
| **M2: MVP** | 4 | AI agent produces evidence-backed diagnoses using MCP tools |
| **M3: In the workflow** | 6 | Diagnoses appear on PRs; feedback improves results |
| **M4: Production** | 9 | Separate services deployed on AWS with tracing and autoscaling |
| **v1.0** | 10 | Hardened, documented, released |

## 9. Hours summary

| Phase | Hours |
|---|---|
| 0. Foundations | 8 |
| 1. Failure ingestion | 12 |
| 2. Log processing and classification | 12 |
| 3. MCP GitHub server | 14 |
| 4. AI diagnosis agent | 18 |
| 5. RAG | 16 |
| 6. GitHub output and feedback | 12 |
| 7. Dashboard | 12 |
| 8. Evals and test coverage | 10 |
| 9. Services and AWS | 16 |
| 10. Hardening and release | 6 |
| **Total** | **136** |

## 10. Ideas after v1.0

- GitLab CI and Jenkins support (the ingestion and MCP layers are designed to be swapped)
- Slack/Teams notifications
- Self-hosted LLM option for private code
- "Explain this flaky test" deep-dive mode
- VS Code extension that shows the diagnosis next to the failing code

## 11. Engineering rules for this repo

- No secrets in git. Use `.env.example` and Secrets Manager. CI runs secret scanning.
- Every phase ships with tests. Main must always build.
- Small PRs with clear commit messages; each phase is tracked as a GitHub milestone.
- Log text is redacted **before** it's sent to any LLM.
- Significant design decisions are recorded as short ADRs in `docs/`.
