# 2. One shared database until Phase 9; migrations live in `common`

**Status:** accepted (Phase 1)

## Context

api-service writes runs and the worker writes jobs and logs. They work on closely
related tables, and both need the same schema version.

## Decision

Use one PostgreSQL database. Flyway migrations live in `common`
(`db/migration`), and every service that uses the database runs them at startup.
Flyway takes a database lock, so concurrent starts are safe. Each service owns its own
SQL (`JdbcClient`, no shared repositories) so the code can split cleanly later.

pgvector is enabled from the first migration because Phase 5 stores embeddings in the
same database. That avoids running a separate vector store.

## Consequences

- Simple to run and to test: one container.
- Schema changes must stay backward compatible for a release, because services may be
  deployed at different times.
- If the services' data needs drift apart, Phase 9 can give each its own schema.
