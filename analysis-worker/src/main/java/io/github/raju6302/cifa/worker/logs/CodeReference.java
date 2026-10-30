package io.github.raju6302.cifa.worker.logs;

/**
 * A file (and optionally a line) the failure points at. Phase 5 uses these to fetch the
 * right code for the agent.
 *
 * @param path repo-relative when the log printed an absolute workspace path; otherwise as
 *             printed, i.e. relative to the step's working directory
 * @param line 1-based, or null when only the file is known
 */
public record CodeReference(String path, Integer line) {

    @Override
    public String toString() {
        return line == null ? path : path + ":" + line;
    }
}
