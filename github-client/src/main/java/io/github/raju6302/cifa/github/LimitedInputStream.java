package io.github.raju6302.cifa.github;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Fails once more than {@code limit} bytes are read, so a huge download can't exhaust memory or disk. */
final class LimitedInputStream extends FilterInputStream {

    private final long limit;
    private long count;

    LimitedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b != -1) {
            count(1);
        }
        return b;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        int read = super.read(buffer, offset, length);
        if (read > 0) {
            count(read);
        }
        return read;
    }

    private void count(int bytes) throws IOException {
        count += bytes;
        if (count > limit) {
            throw new IOException("Download exceeds the " + limit + "-byte limit");
        }
    }
}
