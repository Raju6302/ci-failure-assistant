package io.github.raju6302.cifa.github.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Every file and directory under a commit, from the recursive git trees API.
 *
 * @param truncated true when the repo is too big for one response (over 100,000 entries
 *                  or 7 MB); the listed entries are then incomplete
 */
public record GitTree(String sha, boolean truncated, @JsonProperty("tree") List<Entry> entries) {

    /**
     * @param type blob (file), tree (directory) or commit (submodule)
     * @param size size in bytes; null for anything but blobs
     */
    public record Entry(String path, String mode, String type, String sha, Long size) {

        public boolean isFile() {
            return "blob".equals(type);
        }
    }
}
