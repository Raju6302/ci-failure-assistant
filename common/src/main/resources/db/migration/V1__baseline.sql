-- Baseline schema. pgvector is enabled up front because Phase 5 (RAG) stores
-- code and failure embeddings in this database.
CREATE EXTENSION IF NOT EXISTS vector;
