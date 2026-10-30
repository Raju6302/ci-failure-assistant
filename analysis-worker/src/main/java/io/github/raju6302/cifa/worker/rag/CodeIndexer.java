package io.github.raju6302.cifa.worker.rag;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps a repository's code index in step with the commit being diagnosed. Only files whose
 * content changed since the last index are re-chunked and re-embedded, so after the first
 * index a refresh costs one archive download and a few embeddings.
 */
public class CodeIndexer {

    private static final Logger log = LoggerFactory.getLogger(CodeIndexer.class);

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final RepositorySource source;
    private final Embedder embedder;
    private final RagProperties properties;
    private final CodeChunker chunker;

    public CodeIndexer(JdbcClient jdbc, TransactionTemplate transactions, RepositorySource source, Embedder embedder,
                       RagProperties properties) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.source = source;
        this.embedder = embedder;
        this.properties = properties;
        this.chunker = new CodeChunker(properties.chunkLines());
    }

    public record IndexStats(int filesSeen, int filesIndexed, int filesRemoved, int chunks) {
        static final IndexStats UP_TO_DATE = new IndexStats(0, 0, 0, 0);
    }

    /** Brings the index to {@code commitSha}. A no-op when it's already there. */
    public IndexStats refresh(long repositoryId, String repository, Long installationId, String commitSha) {
        String indexed = jdbc.sql("SELECT indexed_commit_sha FROM repository WHERE id = :id")
                .param("id", repositoryId).query(String.class).optional().orElse(null);
        if (commitSha.equals(indexed)) {
            return IndexStats.UP_TO_DATE;
        }
        Map<String, String> knownHashes = new HashMap<>();
        jdbc.sql("SELECT path, content_hash FROM code_file WHERE repository_id = :id")
                .param("id", repositoryId)
                .query(rs -> {
                    knownHashes.put(rs.getString("path"), rs.getString("content_hash"));
                });

        Set<String> seen = new HashSet<>();
        int[] counts = new int[2]; // [files indexed, chunks]
        long maxFileBytes = properties.maxFileSize().toBytes();
        source.forEachFile(repository, commitSha, installationId, file -> {
            if (seen.size() >= properties.maxFiles() || file.content().length > maxFileBytes
                    || !IndexFilter.isIndexable(file.path())) {
                return;
            }
            Optional<String> text = IndexFilter.asText(file.content());
            if (text.isEmpty() || text.get().isBlank()) {
                return;
            }
            seen.add(file.path());
            String hash = sha256(file.content());
            if (hash.equals(knownHashes.get(file.path()))) {
                return;
            }
            counts[1] += indexFile(repositoryId, file.path(), hash, text.get());
            counts[0]++;
        });

        List<String> removed = knownHashes.keySet().stream().filter(path -> !seen.contains(path)).toList();
        transactions.executeWithoutResult(status -> {
            for (String path : removed) {
                jdbc.sql("DELETE FROM code_file WHERE repository_id = :repo AND path = :path")
                        .param("repo", repositoryId).param("path", path).update();
            }
            jdbc.sql("UPDATE repository SET indexed_commit_sha = :sha, indexed_at = now() WHERE id = :id")
                    .param("sha", commitSha).param("id", repositoryId).update();
        });
        IndexStats stats = new IndexStats(seen.size(), counts[0], removed.size(), counts[1]);
        log.info("Indexed {} at {}: {}", repository, commitSha, stats);
        return stats;
    }

    private int indexFile(long repositoryId, String path, String hash, String content) {
        List<CodeChunker.Chunk> chunks = chunker.chunk(path, content);
        // The path is part of what's embedded: file names carry a lot of meaning in code.
        List<float[]> vectors = embedder.embedAll(chunks.stream().map(chunk -> path + "\n" + chunk.content()).toList());
        transactions.executeWithoutResult(status -> {
            long fileId = jdbc.sql("""
                            INSERT INTO code_file (repository_id, path, content_hash) VALUES (:repo, :path, :hash)
                            ON CONFLICT (repository_id, path) DO UPDATE
                               SET content_hash = EXCLUDED.content_hash, indexed_at = now()
                            RETURNING id""")
                    .param("repo", repositoryId).param("path", path).param("hash", hash)
                    .query(Long.class).single();
            jdbc.sql("DELETE FROM code_chunk WHERE code_file_id = :fileId").param("fileId", fileId).update();
            for (int i = 0; i < chunks.size(); i++) {
                CodeChunker.Chunk chunk = chunks.get(i);
                jdbc.sql("""
                                INSERT INTO code_chunk (code_file_id, repository_id, path, start_line, end_line, symbol,
                                                        content, embedding)
                                VALUES (:fileId, :repo, :path, :start, :end, :symbol, :content, CAST(:embedding AS vector))""")
                        .param("fileId", fileId).param("repo", repositoryId).param("path", path)
                        .param("start", chunk.startLine()).param("end", chunk.endLine()).param("symbol", chunk.symbol())
                        .param("content", chunk.content())
                        .param("embedding", Embedder.toVectorLiteral(vectors.get(i)))
                        .update();
            }
        });
        return chunks.size();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
