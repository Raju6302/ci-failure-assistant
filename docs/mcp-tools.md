# MCP tools (mcp-github-server)

`mcp-github-server` gives the diagnosis agent read-only tools for looking at a failed
build: what changed, the code involved, the workflow, and the job log. It is an
[MCP](https://modelcontextprotocol.io) server built with Spring AI's MCP server starter.

| | |
|---|---|
| Transport | Streamable HTTP |
| URL | `http://localhost:8082/mcp` (Docker Compose) |
| Server name | `cifa-github` |
| Auth | `Authorization: Bearer <MCP_AUTH_TOKEN>` when the token is set |

## Tools

Every tool returns plain text written for a model to read, and every tool is read-only.
`repo` is always `owner/name`.

| Tool | Arguments | Returns |
|---|---|---|
| `get_commit_diff` | `repo`, `sha`, `path_filter`? | Commit message, author, date and parents, then each changed file with its status, `+/-` counts and unified diff. `path_filter` keeps only paths containing that text (case-insensitive). |
| `get_file` | `repo`, `path`, `ref`, `start_line`?, `end_line`? | The file at a branch, tag or sha, every line prefixed with its number (`  42 \| code`) so the agent can cite `path:line`, plus the total line count. |
| `list_recent_commits` | `repo`, `branch`, `limit`? (default 10, max 30) | Newest first: full sha, date, author and the first line of each message. |
| `search_code` | `repo`, `query` | GitHub code search: matching paths with the text fragments around each hit (first 30 files). |
| `get_workflow_file` | `repo`, `run_id` | The workflow YAML a run executed, read at the run's head sha, numbered like `get_file`. `run_id` is GitHub's run id. |
| `get_job_log_section` | `github_job_id`, `start_line`?, `end_line`?, `grep`?, `context_lines`? (default 3, max 20) | Numbered lines of a failed job's stored log. With `grep` (a case-insensitive Java regex) only matching lines (marked `>`) and their context (`\|`); with a range, those lines; with neither, the end of the log, where the error usually is. |

`?` marks optional arguments. Unknown arguments are rejected, so a misspelt one fails
loudly instead of being ignored.

Two things to know about the data:

- **Code search only covers the default branch.** That's a GitHub limitation, so a hit
  may not match the failed commit. Confirm with `get_file` at the run's sha.
- **Logs are the stored copy.** For very long logs only the last 2 MB was kept, so line 1
  is not always where the job started; the tool says so when that's the case.

## Rules

These keep the tools safe to hand to a model, which may be steered by text inside a
build log or a file it reads.

- **Repo allowlist.** Only repositories in the `repository` table (the ones that have
  sent us workflow webhooks) can be read. Each call uses that repository's GitHub App
  installation token, or `GITHUB_TOKEN` when it has no installation. A search query may
  not contain `repo:`, `org:` or `user:`, and hits from other repositories are dropped.
- **Logs come from the database, never from GitHub.** The worker removes secrets before
  storing a log. Fetching the raw log from GitHub again would bypass that.
- **Path deny-list.** `cifa.mcp.denied-paths` (Ant-style globs, case-insensitive,
  default `evals/**`) is applied to `get_file` and `get_workflow_file` (including a
  symlink's target), to the files of `get_commit_diff` and to `search_code` results. The
  eval suite's expected answers must never reach the agent it scores.
- **Output limits.** A tool result is at most `cifa.mcp.max-output-chars` (default
  20,000) characters, a single file's patch at most `cifa.mcp.max-patch-chars` (default
  4,000), and a single line at most 1,000. Text is only cut at line boundaries, and a cut
  result ends with a note saying how to get the rest (for example which `start_line` to
  pass). A grep pattern that runs longer than 2 seconds is stopped.
- **Errors are tool results.** Problems come back as MCP error results (`isError: true`)
  with a short message: not found, not allowed, a temporary GitHub problem (5xx or rate
  limit: try again later), or a bad argument. Tokens, GitHub's error bodies and stack
  traces stay in the server log.
- **Caching.** Commits and files looked up by full 40-character sha are cached in memory
  (`cifa.mcp.cache.ttl`, default 30 minutes, `cifa.mcp.cache.max-size`, default 32 MB per
  cache), as are workflow runs. Lookups by branch name are never cached, because a branch moves.

## Authentication

Set `MCP_AUTH_TOKEN` (property `cifa.mcp.auth-token`) to a random value, for example
`openssl rand -hex 32`, in `.env`. Every request except `/actuator/health` must then send
`Authorization: Bearer <token>`, or gets `401`. The analysis worker sends the same token.
Without a token the server logs a warning at startup and accepts every request, which is
only acceptable on your own machine (Compose binds the port to `127.0.0.1`).

## Try it with an MCP client

Start the stack (or just Postgres plus the server) and connect any MCP client. With the
[MCP Inspector](https://github.com/modelcontextprotocol/inspector):

```bash
docker compose -f infra/docker-compose.yml up -d postgres
MCP_AUTH_TOKEN=dev-token GITHUB_TOKEN=$(gh auth token) ./mvnw -pl mcp-github-server -am spring-boot:run

npx @modelcontextprotocol/inspector
```

In the Inspector, choose transport **Streamable HTTP**, URL `http://localhost:8082/mcp`,
and under **Authentication** set the header `Authorization` to `Bearer dev-token`. Then
open **Tools → List Tools**.

The tools only answer for repositories the system knows. Send a webhook first (see
[local development](local-development.md)), or add a row by hand for a quick test:

```sql
INSERT INTO repository (github_id, full_name) VALUES (1, '<you>/ci-failure-assistant');
```

`get_job_log_section` needs an ingested failed job: take its id from `githubJobId` in
`GET /api/runs/{id}`, or from the job's URL on GitHub.

## Connecting from code

With the MCP Java SDK:

```java
var transport = HttpClientStreamableHttpTransport.builder("http://localhost:8082")
        .endpoint("/mcp")
        .httpRequestCustomizer((request, method, uri, body, context) ->
                request.header("Authorization", "Bearer " + token))
        .build();
try (McpSyncClient client = McpClient.sync(transport).build()) {
    client.initialize();
    CallToolResult result = client.callTool(new CallToolRequest("get_file",
            Map.of("repo", "octo/shop", "path", "pom.xml", "ref", "main")));
}
```
