package io.github.raju6302.cifa.worker.logs;

/**
 * One normalized log line.
 *
 * @param number  1-based line number in the stored log, so excerpts can be cited
 * @param content the text without timestamp, ANSI codes or workflow command prefix
 * @param command the GitHub workflow command that prefixed the line ({@code ##[error]} → {@code error}), or null
 */
record LogLine(int number, String content, String command) {

    boolean isError() {
        return "error".equals(command);
    }

    boolean isGroupStart() {
        return "group".equals(command);
    }

    boolean isBlank() {
        return content.isBlank();
    }
}
