package io.github.raju6302.cifa.github;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reads a stream and keeps only its last {@code capacity} bytes, so a 50 MB log never
 * has to fit in memory. CI errors are almost always near the end of a log.
 */
final class TailBuffer {

    private final byte[] ring;
    private long total;

    TailBuffer(int capacity) {
        this.ring = new byte[capacity];
    }

    void readFully(InputStream in) throws IOException {
        byte[] chunk = new byte[8192];
        int read;
        while ((read = in.read(chunk)) != -1) {
            write(chunk, read);
        }
    }

    private void write(byte[] chunk, int length) {
        for (int i = 0; i < length; i++) {
            ring[(int) (total++ % ring.length)] = chunk[i];
        }
    }

    long totalBytes() {
        return total;
    }

    boolean truncated() {
        return total > ring.length;
    }

    /** The kept bytes as text. When truncated, the first partial line is dropped. */
    String text() {
        if (!truncated()) {
            return new String(ring, 0, (int) total, StandardCharsets.UTF_8);
        }
        int start = (int) (total % ring.length);
        byte[] ordered = new byte[ring.length];
        System.arraycopy(ring, start, ordered, 0, ring.length - start);
        System.arraycopy(ring, 0, ordered, ring.length - start, start);
        String text = new String(ordered, StandardCharsets.UTF_8);
        int firstNewline = text.indexOf('\n');
        return firstNewline >= 0 ? text.substring(firstNewline + 1) : text;
    }
}
