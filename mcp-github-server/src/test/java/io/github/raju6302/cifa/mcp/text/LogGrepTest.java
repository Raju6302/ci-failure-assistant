package io.github.raju6302.cifa.mcp.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class LogGrepTest {

    static final Lines LOG = Lines.of("""
            Run mvn verify
            [INFO] Compiling 12 source files
            [ERROR] Order.java:[14,9] cannot find symbol
            [INFO] BUILD FAILURE
            [error] second error
            done
            """);

    @Test
    void findsMatchingLinesWithinTheRange() {
        Pattern error = Pattern.compile("error", Pattern.CASE_INSENSITIVE);

        assertThat(LogGrep.matches(LOG, error, 1, LOG.count(), Duration.ofSeconds(1))).containsExactly(3, 5);
        assertThat(LogGrep.matches(LOG, error, 4, LOG.count(), Duration.ofSeconds(1))).containsExactly(5);
    }

    @Test
    void addsContextAndMergesOverlappingRanges() {
        assertThat(LogGrep.withContext(List.of(3, 5), 1, 1, 6))
                .containsExactly(new LogGrep.Range(2, 6));
        assertThat(LogGrep.withContext(List.of(2, 20), 2, 1, 21))
                .containsExactly(new LogGrep.Range(1, 4), new LogGrep.Range(18, 21));
        assertThat(LogGrep.withContext(List.of(3), 0, 1, 6)).containsExactly(new LogGrep.Range(3, 3));
    }

    @Test
    void givesUpOnPatternsThatBacktrackForever() {
        // Runs for hours on java.util.regex without the deadline.
        Lines hostile = Lines.of(",".repeat(80));
        Pattern catastrophic = Pattern.compile("(.*,){12}X");

        assertThatThrownBy(() -> LogGrep.matches(hostile, catastrophic, 1, 1, Duration.ofMillis(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("took too long");
    }
}
