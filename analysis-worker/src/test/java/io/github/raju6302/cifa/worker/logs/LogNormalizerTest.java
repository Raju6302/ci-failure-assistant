package io.github.raju6302.cifa.worker.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LogNormalizerTest {

    @Test
    void stripsBomTimestampsAndAnsiCodesAndKeepsLineNumbers() {
        String log = "\uFEFF2026-10-30T13:29:46.1461365Z Current runner version: '2.338.0'\n"
                + "2026-10-30T13:29:48.7316517Z \u001B[36;1mmvn -B verify\u001B[0m\n"
                + "\n"
                + "2026-10-30T13:29:58.1476758Z ##[error][ERROR] Foo.java:[9,34] cannot find symbol\n";

        List<LogLine> lines = LogNormalizer.normalize(log);

        assertThat(lines).containsExactly(
                new LogLine(1, "Current runner version: '2.338.0'", null),
                new LogLine(2, "mvn -B verify", null),
                new LogLine(3, "", null),
                new LogLine(4, "[ERROR] Foo.java:[9,34] cannot find symbol", "error"));
    }

    @Test
    void recognizesWorkflowCommandsAndWindowsLineEnds() {
        List<LogLine> lines = LogNormalizer.normalize("##[group]Run npm test\r\n##[endgroup]\r\nplain\r\n");

        assertThat(lines).extracting(LogLine::command).containsExactly("group", "endgroup", null);
        assertThat(lines.getFirst().content()).isEqualTo("Run npm test");
        assertThat(lines.getLast().content()).isEqualTo("plain");
    }

    @Test
    void stripsOscHyperlinksAndCursorCodes() {
        String line = "\u001B]8;;https://example.com\u0007link\u001B]8;;\u0007 \u001B[2K\u001B[1Gdone";

        assertThat(LogNormalizer.normalize(line).getFirst().content()).isEqualTo("link done");
    }

    @Test
    void cutsVeryLongLines() {
        String huge = "x".repeat(5 * 1024 * 1024);

        LogLine line = LogNormalizer.normalize(huge).getFirst();

        assertThat(line.content()).hasSize(LogNormalizer.MAX_LINE_LENGTH + 1).endsWith("…");
    }

    @Test
    void handlesEmptyInput() {
        assertThat(LogNormalizer.normalize(null)).isEmpty();
        assertThat(LogNormalizer.normalize("")).isEmpty();
    }
}
