# 5. RAG in Postgres with local embeddings

**Status:** accepted (Phase 5)

## Context

The agent diagnoses better when it starts with the right code and knows whether the
failure happened before. Anthropic has no embeddings endpoint, and sending a whole
repository to a third-party embedding API would be a cost and a privacy concern.

## Decision

- **Store:** the existing PostgreSQL database with pgvector (HNSW indexes) for vectors,
  and a generated `tsvector` column for keyword search. No separate vector database.
- **Embeddings:** all-MiniLM-L6-v2 (384 dimensions) running locally via ONNX (Spring AI
  Transformers). A deterministic hashing embedder exists for tests and offline use.
- **Indexing:** at the failing commit, from one zip-archive download; only files whose
  content hash changed are re-embedded. Vendored, generated and binary files are
  skipped, and so is `evals/`, which holds the expected answers.
- **Retrieval:** vector and keyword rankings merged with reciprocal rank fusion, then
  chunks the log points to (compiler errors, stack traces) are boosted to the top.
- **Failure memory:** each diagnosis is stored with its error fingerprint and an
  embedding. Recurring failures are found by exact fingerprint first, then by similarity.

## Consequences

- No extra API key or service to run. The model (~90 MB) downloads on first start.
- Changing to an embedding model with another size needs a migration and a re-index.
- Indexing on demand at the failing commit means the index always matches the code that
  failed. The first index of a large repository takes minutes, bounded by `max-files`.
