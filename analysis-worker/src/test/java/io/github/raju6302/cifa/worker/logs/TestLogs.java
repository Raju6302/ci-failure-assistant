package io.github.raju6302.cifa.worker.logs;

import java.util.List;

/** Builds small synthetic logs for parser tests. */
final class TestLogs {

    static final String WORKSPACE = "/home/runner/work/shop/shop/";

    private TestLogs() {
    }

    /** A step named {@code name} whose output is {@code text}, numbered from line 1. */
    static StepLog step(String name, String text) {
        List<LogLine> lines = LogNormalizer.normalize(text);
        return new StepLog(name, lines);
    }

    /** A job log in GitHub's format: a setup line, then one step with a header and {@code output}. */
    static String jobLog(String command, String output) {
        StringBuilder log = new StringBuilder("2026-10-30T10:00:00.0000000Z Current runner version: '2.338.0'\n")
                .append("2026-10-30T10:00:01.0000000Z ##[group]Run ").append(command).append('\n')
                .append("2026-10-30T10:00:01.0000000Z shell: /usr/bin/bash -e {0}\n")
                .append("2026-10-30T10:00:01.0000000Z ##[endgroup]\n");
        output.lines().forEach(line -> log.append("2026-10-30T10:00:02.0000000Z ").append(line).append('\n'));
        return log.append("2026-10-30T10:00:03.0000000Z ##[error]Process completed with exit code 1.\n")
                .append("2026-10-30T10:00:03.1000000Z Post job cleanup.\n")
                .toString();
    }

    static JobEvidence evidence(String command, String output) {
        return new JobEvidence("failure", List.of("Build and test"), jobLog(command, output), List.of());
    }
}
