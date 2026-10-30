package io.github.raju6302.cifa.github.model;

import java.time.Instant;
import java.util.List;

/**
 * One commit with its changed files. GitHub lists at most 300 files on the first page of a
 * commit; the rest are only reachable through pagination, which a diagnosis doesn't need.
 */
public record Commit(
        String sha,
        String message,
        String authorName,
        Instant authoredAt,
        List<String> parentShas,
        List<ChangedFile> files) {

    /**
     * @param status           added, modified, removed, renamed, copied, changed or unchanged
     * @param previousFilename the old path of a renamed file, otherwise null
     * @param patch            the unified diff; null for binary files and diffs GitHub considers too large
     */
    public record ChangedFile(
            String filename,
            String status,
            int additions,
            int deletions,
            String previousFilename,
            String patch) {
    }
}
