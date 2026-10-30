# Retrieval (RAG)

## Code index

Before a run is diagnosed, the repository's index is brought to the failing commit:

1. download the repository as one zip archive from GitHub (capped at `max-archive-size`);
2. keep source and build/CI files (Java, Kotlin, JS/TS, Python, Go, …, `pom.xml`,
   `package.json`, `Dockerfile`, workflow YAML); skip `node_modules`, `vendor`, build
   output, lock files, binaries, files over `max-file-size`, and `evals/`;
3. skip files whose SHA-256 is unchanged since the last index;
4. split changed files into chunks of ~60 lines, breaking at class/function declarations;
5. embed `path + chunk` locally and store chunks in `code_chunk` (pgvector + full-text).

## Retrieval

For a failed job, the query is the analysis summary plus the log excerpt:

- **vector**: nearest chunks by cosine distance;
- **keyword**: Postgres full-text on distinctive identifiers from the error;
- both rankings are merged with **reciprocal rank fusion**;
- chunks in files the log points to (e.g. `InvoiceService.java:[9,34]`) are boosted, most
  when they contain the referenced line.

The top 6 snippets go into the agent's first message, marked as possibly irrelevant.

## Failure memory

Each diagnosis is stored in `failure_memory` with the Phase 2 fingerprint and an
embedding of the category and summary. For a new failure, the agent gets up to 3 past
failures: exact fingerprint matches first, then embeddings within `memory-max-distance`.
Phase 6 adds the confirmed fix (`resolution`) once the next run on the branch passes.

## Configuration (`cifa.rag.*`)

`enabled`, `embedder` (`onnx` or `hashing`), `max-files`, `max-file-size`,
`max-archive-size`, `chunk-lines`, `top-k`, `max-snippet-chars`, `memory-results`,
`memory-max-distance`, `model-cache-dir`.
