-- Phase 4: AI diagnosis of failed jobs.

ALTER TABLE workflow_run DROP CONSTRAINT workflow_run_status_check;
ALTER TABLE workflow_run ADD CONSTRAINT workflow_run_status_check
    CHECK (status IN ('RECEIVED', 'QUEUED', 'INGESTING', 'INGESTED', 'INGEST_FAILED',
                      'DIAGNOSING', 'DIAGNOSED', 'DIAGNOSIS_FAILED'));
ALTER TABLE workflow_run ADD COLUMN diagnosis_attempts INT NOT NULL DEFAULT 0;
ALTER TABLE workflow_run ADD COLUMN diagnosis_started_at TIMESTAMPTZ;
ALTER TABLE workflow_run ADD COLUMN diagnosed_at TIMESTAMPTZ;

-- One diagnosis per failed job. source = AGENT when the LLM agent produced it,
-- RULES when it fell back to the Phase 2 rule-based analysis (agent_error says why).
CREATE TABLE diagnosis (
    id                 BIGSERIAL PRIMARY KEY,
    failed_job_id      BIGINT      NOT NULL UNIQUE REFERENCES failed_job (id) ON DELETE CASCADE,
    source             TEXT        NOT NULL CHECK (source IN ('AGENT', 'RULES')),
    category           TEXT        NOT NULL,
    root_cause         TEXT        NOT NULL,
    confidence         TEXT        NOT NULL CHECK (confidence IN ('HIGH', 'MEDIUM', 'LOW')),
    evidence           JSONB       NOT NULL DEFAULT '[]',
    suggested_fix      TEXT        NOT NULL,
    patch              TEXT,
    model              TEXT,
    steps              INT         NOT NULL DEFAULT 0,
    tool_calls         JSONB       NOT NULL DEFAULT '[]',
    input_tokens       BIGINT      NOT NULL DEFAULT 0,
    output_tokens      BIGINT      NOT NULL DEFAULT 0,
    cache_read_tokens  BIGINT      NOT NULL DEFAULT 0,
    cache_write_tokens BIGINT      NOT NULL DEFAULT 0,
    cost_usd           NUMERIC(10, 4) NOT NULL DEFAULT 0,
    latency_ms         BIGINT      NOT NULL DEFAULT 0,
    agent_error        TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
