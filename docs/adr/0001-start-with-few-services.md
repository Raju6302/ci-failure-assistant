# 1. Start with a few deployable services, not full microservices

**Status:** accepted (Phase 0)

## Context

The system has clear parts: receiving webhooks, slow analysis work (log download, LLM
calls), GitHub tools for the agent, and a UI. A full microservice setup from day one
(service discovery, gateway, Kafka, Kubernetes, a database per service) would take
weeks of setup before the product does anything, for a project with one developer.

## Decision

Start with four separately deployable parts in one Maven multi-module repo:
`api-service`, `analysis-worker`, `mcp-github-server` and `dashboard`. Shared code
lives in libraries (`common`, `github-client`). The parts talk only through a queue,
the database and HTTP (MCP), never by calling each other's classes.

## Consequences

- Fast webhook responses: slow work runs in the worker, so the webhook endpoint answers in milliseconds.
- The worker can be scaled on its own when many builds fail at once.
- The boundaries already match the target design, so Phase 9 (separate deployments on
  AWS, SQS instead of Redis) changes infrastructure, not code structure.
- For now, the services share one database (see ADR 2).
