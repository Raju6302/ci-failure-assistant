#!/usr/bin/env bash
# Sends a signed workflow_run webhook to a local api-service, the same way GitHub would.
#
#   GITHUB_WEBHOOK_SECRET=... scripts/send-test-webhook.sh [payload.json] [url]
#
# The default payload describes a made-up run, so the worker's GitHub calls will fail
# and the run ends up INGEST_FAILED. That's enough to test the webhook → queue path.
# For a full test, set GITHUB_TOKEN and pass a payload for a real failed run (save one
# from your repo's webhook "Recent deliveries" page), or use `gh webhook forward`:
# see docs/local-development.md.
set -euo pipefail

payload="${1:-api-service/src/test/resources/webhooks/workflow_run_failed.json}"
url="${2:-http://localhost:8080/webhooks/github}"
: "${GITHUB_WEBHOOK_SECRET:?Set GITHUB_WEBHOOK_SECRET to the secret the api-service uses}"

signature="sha256=$(openssl dgst -sha256 -hmac "$GITHUB_WEBHOOK_SECRET" -r < "$payload" | cut -d' ' -f1)"

curl -sS -X POST "$url" \
  -H "Content-Type: application/json" \
  -H "X-GitHub-Event: workflow_run" \
  -H "X-GitHub-Delivery: $(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)" \
  -H "X-Hub-Signature-256: $signature" \
  --data-binary "@$payload"
echo
