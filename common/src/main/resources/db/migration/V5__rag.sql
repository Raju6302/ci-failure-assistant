-- Phase 5: retrieval for the diagnosis agent.
-- Embeddings are 384-dimensional: the size of the default local model (all-MiniLM-L6-v2).
-- Changing the embedding model to one with another size needs a migration and a re-index.

ALTER TABLE repository ADD COLUMN indexed_commit_sha TEXT;
ALTER TABLE repository ADD COLUMN indexed_at TIMESTAMPTZ;

-- One row per indexed file; content_hash lets a re-index skip unchanged files.
CREATE TABLE code_file (
    id            BIGSERIAL PRIMARY KEY,
    repository_id BIGINT      NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    path          TEXT        NOT NULL,
    content_hash  TEXT        NOT NULL,
    indexed_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (repository_id, path)
);

CREATE TABLE code_chunk (
    id            BIGSERIAL PRIMARY KEY,
    code_file_id  BIGINT  NOT NULL REFERENCES code_file (id) ON DELETE CASCADE,
    repository_id BIGINT  NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    path          TEXT    NOT NULL,
    start_line    INT     NOT NULL,
    end_line      INT     NOT NULL,
    symbol        TEXT,
    content       TEXT    NOT NULL,
    -- 'simple' config: no stemming or stop words, so code identifiers match exactly.
    content_tsv   TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', path || ' ' || content)) STORED,
    embedding     VECTOR(384) NOT NULL
);

CREATE INDEX code_chunk_repository_idx ON code_chunk (repository_id, path);
CREATE INDEX code_chunk_tsv_idx ON code_chunk USING gin (content_tsv);
CREATE INDEX code_chunk_embedding_idx ON code_chunk USING hnsw (embedding vector_cosine_ops);

-- What we learned from past failures. resolution is filled in once a fix is confirmed (Phase 6).
CREATE TABLE failure_memory (
    id            BIGSERIAL PRIMARY KEY,
    repository_id BIGINT      NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    failed_job_id BIGINT      NOT NULL UNIQUE REFERENCES failed_job (id) ON DELETE CASCADE,
    fingerprint   TEXT,
    category      TEXT        NOT NULL,
    summary       TEXT        NOT NULL,
    root_cause    TEXT        NOT NULL,
    resolution    TEXT,
    embedding     VECTOR(384) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX failure_memory_fingerprint_idx ON failure_memory (repository_id, fingerprint);
CREATE INDEX failure_memory_embedding_idx ON failure_memory USING hnsw (embedding vector_cosine_ops);
