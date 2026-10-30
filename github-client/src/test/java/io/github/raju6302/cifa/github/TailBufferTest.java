package io.github.raju6302.cifa.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TailBufferTest {

    @Test
    void keepsEverythingWhenSmallerThanCapacity() throws Exception {
        TailBuffer tail = read("line 1\nline 2\n", 100);

        assertThat(tail.truncated()).isFalse();
        assertThat(tail.totalBytes()).isEqualTo(14);
        assertThat(tail.text()).isEqualTo("line 1\nline 2\n");
    }

    @Test
    void keepsOnlyTheEndAndDropsThePartialFirstLine() throws Exception {
        StringBuilder log = new StringBuilder();
        for (int i = 1; i <= 1000; i++) {
            log.append("step output ").append(i).append('\n');
        }
        log.append("[ERROR] BUILD FAILURE\n");

        TailBuffer tail = read(log.toString(), 64);

        assertThat(tail.truncated()).isTrue();
        assertThat(tail.totalBytes()).isEqualTo(log.length());
        assertThat(tail.text()).endsWith("[ERROR] BUILD FAILURE\n").startsWith("step output");
    }

    private static TailBuffer read(String text, int capacity) throws Exception {
        TailBuffer tail = new TailBuffer(capacity);
        tail.readFully(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
        return tail;
    }
}
