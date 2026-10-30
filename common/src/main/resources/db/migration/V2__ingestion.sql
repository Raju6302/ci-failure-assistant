-- Phase 1: receiving failed workflow runs and storing their job logs.

CREATE TABLE repository (
    id              BIGSERIAL PRIMARY KEY,
    github_id       BIGINT      NOT NULL UNIQUE,
    full_name       TEXT        NOT NULL,
    installation_id BIGINT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- GitHub can deliver the same webhook more than once; the delivery id makes handling idempotent.
CREATE TABLE webhook_delivery (
    delivery_id TEXT PRIMARY KEY,
    event       TEXT        NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workflow_run (
    id                   BIGSERIAL PRIMARY KEY,
    repository_id        BIGINT      NOT NULL REFERENCES repository (id),
    github_run_id        BIGINT      NOT NULL,
    run_attempt          INT         NOT NULL,
    run_number           INT         NOT NULL,
    workflow_name        TEXT        NOT NULL,
    event                TEXT        NOT NULL,
    head_branch          TEXT,
    head_sha             TEXT        NOT NULL,
    conclusion           TEXT        NOT NULL,
    html_url             TEXT        NOT NULL,
    pull_request_numbers INT[]       NOT NULL DEFAULT '{}',
    status               TEXT        NOT NULL DEFAULT 'RECEIVED',
    ingest_attempts      INT         NOT NULL DEFAULT 0,
    error_message        TEXT,
    received_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    queued_at            TIMESTAMPTZ,
    ingested_at          TIMESTAMPTZ,
    UNIQUE (repository_id, github_run_id, run_attempt),
    CONSTRAINT workflow_run_status_check
        CHECK (status IN ('RECEIVED', 'QUEUED', 'INGESTING', 'INGESTED', 'INGEST_FAILED'))
);

CREATE INDEX workflow_run_received_at_idx ON workflow_run (received_at DESC);
-- Small partial index for the relay that re-queues runs stuck before the queue.
CREATE INDEX workflow_run_pending_idx ON workflow_run (received_at) WHERE status = 'RECEIVED';

CREATE TABLE failed_job (
    id              BIGSERIAL PRIMARY KEY,
    workflow_run_id BIGINT      NOT NULL REFERENCES workflow_run (id) ON DELETE CASCADE,
    github_job_id   BIGINT      NOT NULL UNIQUE,
    name            TEXT        NOT NULL,
    conclusion      TEXT        NOT NULL,
    failed_steps    TEXT[]      NOT NULL DEFAULT '{}',
    html_url        TEXT,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    -- Secrets are redacted before the log is stored. NULL when GitHub no longer has the log.
    log_text        TEXT,
    log_bytes       BIGINT      NOT NULL DEFAULT 0,
    log_truncated   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX failed_job_workflow_run_idx ON failed_job (workflow_run_id);
