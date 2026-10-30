package io.github.raju6302.cifa.mcp.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LimitedTextTest {

    @Test
    void keepsEverythingThatFits() {
        LimitedText out = new LimitedText(2000);
        out.line("first");
        out.lines("second\nthird\n");

        assertThat(out.truncated()).isFalse();
        assertThat(out.finish("unused")).isEqualTo("first\nsecond\nthird\n");
    }

    @Test
    void cutsAtLineBoundariesAndExplainsHowToGetMore() {
        LimitedText out = new LimitedText(1000);
        int added = 0;
        while (out.line("x".repeat(99))) {
            added++;
        }

        String text = out.finish("Pass start_line=7.");

        assertThat(out.truncated()).isTrue();
        assertThat(text.length()).isLessThanOrEqualTo(1000);
        assertThat(text).startsWith(("x".repeat(99) + "\n").repeat(added))
                .endsWith("[Truncated: limited to 1000 characters. Pass start_line=7.]");
        assertThat(out.line("short")).as("nothing is added after the first cut").isFalse();
    }

    @Test
    void shortensVeryLongLinesInsteadOfDroppingThem() {
        LimitedText out = new LimitedText(5000);
        out.line("y".repeat(3000));

        assertThat(out.truncated()).isFalse();
        assertThat(out.finish("")).hasSizeLessThan(1100).contains("[line cut, 2000 more characters]");
    }
}
