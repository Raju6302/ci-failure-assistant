-- Phase 2: rule-based analysis of each failed job (category, error excerpt, fingerprint).

-- Check-run annotations of the job: the only record of why a job was cancelled by its
-- timeout, and problem-matcher hits with file and line. Kept so analysis can be re-run
-- without calling GitHub again. Secrets are redacted, like the log.
ALTER TABLE failed_job ADD COLUMN annotations JSONB NOT NULL DEFAULT '[]';

CREATE TABLE failure_analysis (
    id               BIGSERIAL PRIMARY KEY,
    failed_job_id    BIGINT      NOT NULL UNIQUE REFERENCES failed_job (id) ON DELETE CASCADE,
    category         TEXT        NOT NULL,
    -- As GitHub names it in the job's steps; NULL when neither the log nor the job says.
    failed_step      TEXT,
    summary          TEXT        NOT NULL,
    error_excerpt    TEXT        NOT NULL,
    -- 1-based lines of failed_job.log_text; NULL when the excerpt comes from annotations.
    error_start_line INT,
    error_end_line   INT,
    -- [{"path": "src/main/java/shop/InvoiceService.java", "line": 9}], paths repo-relative
    code_references  JSONB       NOT NULL DEFAULT '[]',
    -- SHA-256 hex: the same failure on another commit gets the same value.
    fingerprint      TEXT        NOT NULL,
    parser           TEXT        NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT failure_analysis_category_check
        CHECK (category IN ('COMPILE_ERROR', 'TEST_FAILURE', 'DEPENDENCY', 'FLAKY_TEST', 'INFRASTRUCTURE',
                            'CONFIG_ERROR', 'UNKNOWN')),
    CONSTRAINT failure_analysis_lines_check
        CHECK (error_start_line IS NULL OR error_end_line >= error_start_line)
);

-- Grouping repeats of the same failure (dashboard insights, Phase 5 failure memory).
CREATE INDEX failure_analysis_fingerprint_idx ON failure_analysis (fingerprint);
