#!/usr/bin/env bash
set -euo pipefail
echo "Waiting for payments-service on localhost:9090..."
until curl -fs http://localhost:9090/health; do
  sleep 5
done
