# GitHub App setup

A GitHub App is the production way to connect the assistant: it gets short-lived,
per-installation tokens with only the permissions it needs, and one App can be installed
on many repositories.

## 1. Create the App

GitHub → **Settings → Developer settings → GitHub Apps → New GitHub App**.

| Field | Value |
|---|---|
| Name | e.g. `ci-failure-assistant-<you>` (must be unique on GitHub) |
| Homepage URL | your fork's URL |
| Webhook URL | public URL that reaches `POST /webhooks/github` (see step 4 for local) |
| Webhook secret | output of `openssl rand -hex 32`; keep it for `.env` |
| Where can it be installed | Only on this account |

**Repository permissions** (keep to the minimum):

| Permission | Access | Needed for |
|---|---|---|
| Actions | Read | Jobs and logs (Phase 1) |
| Contents | Read | Code, diffs, workflow files (Phases 3–5) |
| Metadata | Read | Required by GitHub |
| Pull requests | Read & write | Diagnosis comments (Phase 6) |
| Checks | Read (Read & write from Phase 6) | Job annotations, the only place GitHub reports a job timeout (Phase 2); check-run summaries (Phase 6) |

You can leave Pull requests off until Phase 6. Without **Checks: read**, ingestion still works but job timeouts are skipped like ordinary cancellations.

**Subscribe to events:** `Workflow run`.

## 2. Create a private key

On the App's page, note the **App ID**, then **Generate a private key**. Save the
downloaded `.pem` as `secrets/github-app.pem` in the repo root. The `secrets/` folder
and `*.pem` files are git-ignored. Never commit the key.

## 3. Install it

App page → **Install App** → pick the repositories to watch.

## 4. Configure and run

```bash
# .env
GITHUB_WEBHOOK_SECRET=<the secret from step 1>
GITHUB_APP_ID=<App ID>
GITHUB_APP_PRIVATE_KEY_PATH=/run/secrets/github-app.pem
```

```bash
docker compose -f infra/docker-compose.yml up --build
```

To receive App webhooks on your laptop, point the App's webhook URL at a forwarding
service such as [smee.io](https://smee.io):

```bash
npx smee-client --url https://smee.io/<your-channel> --target http://localhost:8080/webhooks/github
```

## 5. Check it works

Run **Actions → Sandbox failures → Run workflow** in an installed repo. Within seconds of
the jobs failing, the run shows up in the dashboard as `QUEUED`, then `INGESTED`.
The App's **Advanced → Recent deliveries** page shows each delivery and our response
(`accepted`, `duplicate` or `ignored`).
