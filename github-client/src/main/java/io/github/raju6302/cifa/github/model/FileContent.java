package io.github.raju6302.cifa.github.model;

/**
 * A file read through the contents API.
 *
 * @param path the path GitHub resolved; differs from the requested one when that was a symlink
 * @param sha  the blob sha, which identifies this exact content
 * @param size size in bytes
 * @param text the decoded UTF-8 text, or null when the file is binary or larger than
 *             {@link #MAX_TEXT_BYTES} (the contents API only returns content up to 1 MB)
 */
public record FileContent(String path, String sha, long size, String text) {

    public static final int MAX_TEXT_BYTES = 1024 * 1024;

    public boolean isText() {
        return text != null;
    }
}
