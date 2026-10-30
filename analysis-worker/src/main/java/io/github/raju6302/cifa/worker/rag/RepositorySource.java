package io.github.raju6302.cifa.worker.rag;

import java.util.function.Consumer;

/** Where the indexer reads a repository's files at a commit. */
public interface RepositorySource {

    /** Calls {@code visitor} for each regular file (repo-relative path, raw bytes). */
    void forEachFile(String repository, String ref, Long installationId, Consumer<SourceFile> visitor);

    record SourceFile(String path, byte[] content) {
    }
}
