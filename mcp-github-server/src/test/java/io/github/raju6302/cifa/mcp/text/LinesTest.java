package io.github.raju6302.cifa.mcp.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LinesTest {

    @Test
    void splitsWithoutAnEmptyLastLineAndDropsCarriageReturns() {
        Lines lines = Lines.of("a\r\nb\n\nc\n");

        assertThat(lines.all()).containsExactly("a", "b", "", "c");
        assertThat(Lines.of("").count()).isZero();
        assertThat(Lines.of(null).count()).isZero();
    }

    @Test
    void padsLineNumbersToTheWidestOne() {
        Lines lines = Lines.of(numbered(120));

        assertThat(lines.numbered(7)).isEqualTo("  7 | line 7");
        assertThat(lines.numbered(120)).isEqualTo("120 | line 120");
        assertThat(lines.numbered(42, '>')).isEqualTo(" 42 > line 42");
    }

    @Test
    void appendsARangeAndReportsWhereItStopped() {
        Lines lines = Lines.of(numbered(500));
        LimitedText out = new LimitedText(2000);

        int last = lines.appendNumbered(out, 10, 500);

        assertThat(last).isBetween(10, 499);
        assertThat(out.finish("")).startsWith(" 10 | line 10\n").contains("%3d | line %d\n".formatted(last, last))
                .doesNotContain("line %d\n".formatted(last + 1));
    }

    @Test
    void findsTheLongestTailThatFits() {
        Lines lines = Lines.of(numbered(100));
        // "100 | line 100" plus a line break is 15 characters; " 99 | line 99" plus one is 14.
        assertThat(lines.tailStart(15)).isEqualTo(100);
        assertThat(lines.tailStart(29)).isEqualTo(99);
        assertThat(lines.tailStart(1_000_000)).isEqualTo(1);
    }

    private static String numbered(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "line " + i).collect(Collectors.joining("\n"));
    }
}
