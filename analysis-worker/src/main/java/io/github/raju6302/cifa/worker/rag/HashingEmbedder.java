package io.github.raju6302.cifa.worker.rag;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feature-hashing "embedding": tokens are hashed into 384 buckets and the vector is
 * normalized. No model download and fully deterministic, so tests and offline setups can
 * run the whole RAG pipeline. It matches shared words, not meaning; use {@link OnnxEmbedder}
 * for real retrieval quality.
 */
public class HashingEmbedder implements Embedder {

    private static final Pattern TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9]{1,40}");
    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        Matcher tokens = TOKEN.matcher(text == null ? "" : text);
        while (tokens.find()) {
            String token = tokens.group();
            add(vector, token.toLowerCase());
            for (String part : CAMEL.split(token)) {
                if (part.length() > 2 && part.length() < token.length()) {
                    add(vector, part.toLowerCase());
                }
            }
        }
        double norm = 0;
        for (float value : vector) {
            norm += value * value;
        }
        if (norm > 0) {
            float scale = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        } else {
            vector[0] = 1; // pgvector can't compute cosine distance for a zero vector
        }
        return vector;
    }

    private static void add(float[] vector, String token) {
        int hash = murmurLike(token.getBytes(StandardCharsets.UTF_8));
        int bucket = Math.floorMod(hash, DIMENSIONS);
        vector[bucket] += (hash & 0x40000000) == 0 ? 1 : -1;
    }

    private static int murmurLike(byte[] bytes) {
        int h = 0x9747b28c;
        for (byte b : bytes) {
            h ^= b;
            h *= 0x5bd1e995;
            h ^= h >>> 15;
        }
        return h;
    }
}
