# Diagnosis agent

After a failed run is ingested and its logs analyzed (Phase 2), the run goes to the
diagnosis queue. For each failed job, the worker:

1. builds the **context**: run and job metadata, the rule-based analysis (category guess,
   summary, log excerpt, file references), up to 6 related code snippets retrieved by RAG,
   and up to 3 similar past failures;
2. runs the **agent**: Claude with the MCP tools from `mcp-github-server`
   (`get_job_log_section`, `get_file`, `get_commit_diff`, `list_recent_commits`,
   `search_code`, `get_workflow_file`), until it calls `submit_diagnosis`;
3. stores the **diagnosis**: root cause, category, confidence, evidence (exact quotes with
   paths and lines), suggested fix and an optional patch, plus tokens, cost, steps and the
   tool calls made. The diagnosis is also saved to the failure memory.

## Configuration (`cifa.diagnosis.*`)

| Property | Default | Notes |
|---|---|---|
| `agent-enabled` | `true` | `false` = rule-based diagnoses only |
| `model` | `claude-opus-5-5` | |
| `effort` | `high` | `low` … `max` |
| `max-steps` | `15` | model calls per job |
| `token-budget` | `400000` | when reached, the model is asked to submit what it has |
| `server-side-fallback` | `true` | on a safety refusal, retry on Anthropic's recommended fallback model |
| `max-attempts` | `3` | per run, for temporary API errors |
| `mcp.url` / `mcp.auth-token` | `http://localhost:8082/mcp` / none | |
| `pricing.*` | Opus 5.5 list prices | USD per million tokens, for cost tracking |

Credentials: set `ANTHROPIC_API_KEY`. Without credentials (or with the agent disabled),
every job still gets a **rule-based diagnosis** at LOW confidence, and `agent_error` says why.

## Failure handling

| Situation | Result |
|---|---|
| Rate limit, overload, API unreachable, MCP server down | Run stays `DIAGNOSING`; retried after the reclaim timeout, up to `max-attempts` |
| Refusal, bad request, no credentials, step limit, invalid output | Rule-based diagnosis for that job, reason stored |
| Worker crash mid-run | Queue entry reclaimed; already-diagnosed jobs are skipped |

## Security

- Logs, code and commit messages are untrusted. The system prompt tells the model never to
  follow instructions found in them, and they're delimited in the prompt.
- The agent only sees **redacted** logs: `get_job_log_section` reads the stored log, never GitHub.
- `evals/` is excluded from both the MCP tools and the code index.
