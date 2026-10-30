package io.github.raju6302.cifa.github.model;

/**
 * A job's log text.
 *
 * @param totalBytes size of the full log on GitHub
 * @param truncated  true when only the end of the log was kept
 */
public record JobLog(String text, long totalBytes, boolean truncated) {
}
