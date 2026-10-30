# Local development

## Prerequisites

- Java 21 (`./mvnw` downloads Maven for you)
- Docker (for Postgres, Redis and the integration tests)
- Node 24 (for the dashboard)

## Run everything in Docker

```bash
cp .env.example .env
docker compose -f infra/docker-compose.yml up --build
```

Dashboard on http://localhost:3000, API on http://localhost:8080.

## Run services from your IDE

Start only the infrastructure in Docker:

```bash
docker compose -f infra/docker-compose.yml up -d postgres redis
```

Then run the apps (each picks up `localhost` defaults):

```bash
GITHUB_WEBHOOK_SECRET=dev-secret ./mvnw -pl api-service -am spring-boot:run
GITHUB_TOKEN=$(gh auth token) ./mvnw -pl analysis-worker -am spring-boot:run
cd dashboard && npm install && npm run dev      # http://localhost:5173, proxies /api to :8080
```

## Get real webhooks without a GitHub App

The quickest way to see real failures is a repository webhook forwarded by the GitHub CLI:

```bash
gh extension install cli/gh-webhook
gh webhook forward --repo=<you>/ci-failure-assistant --events=workflow_run \
  --url=http://localhost:8080/webhooks/github --secret="$GITHUB_WEBHOOK_SECRET"
```

Then, on GitHub, open **Actions → Sandbox failures → Run workflow**. When the jobs fail,
the run appears in the dashboard and the worker downloads its logs using `GITHUB_TOKEN`.
A fine-grained token needs **Actions: read** and **Contents: read**.

For a production-like setup, use a GitHub App instead: see [github-app-setup.md](github-app-setup.md).

## Send a test webhook by hand

```bash
GITHUB_WEBHOOK_SECRET=dev-secret scripts/send-test-webhook.sh            # sample payload
GITHUB_WEBHOOK_SECRET=dev-secret scripts/send-test-webhook.sh my-run.json
```

The sample payload describes a made-up run, so its ingestion ends in `INGEST_FAILED`.
That's expected, and it still exercises webhook → database → queue → worker. Use a payload
copied from a real delivery ("Recent deliveries" in the webhook settings) for a full test.

## Tests

```bash
./mvnw verify                 # all backend modules; starts containers via Testcontainers
./mvnw -pl api-service verify # one module
cd dashboard && npm run lint && npm test && npm run build
```

## Run statuses

| Status | Meaning |
|---|---|
| `RECEIVED` | Stored from the webhook, not on the queue yet (or waiting to be retried) |
| `QUEUED` | On the queue |
| `INGESTING` | A worker is fetching jobs and logs |
| `INGESTED` | Failed jobs and their (redacted) logs are stored |
| `INGEST_FAILED` | Gave up; the run's `errorMessage` says why |
