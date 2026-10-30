package io.github.raju6302.cifa.worker.rag;

import java.util.List;

/** Turns text into vectors for similarity search. */
public interface Embedder {

    int DIMENSIONS = 384;

    float[] embed(String text);

    default List<float[]> embedAll(List<String> texts) {
        return texts.stream().map(this::embed).toList();
    }

    /** pgvector literal, e.g. {@code [0.1,0.2,…]}, for {@code CAST(:v AS vector)}. */
    static String toVectorLiteral(float[] vector) {
        StringBuilder out = new StringBuilder(vector.length * 10).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(vector[i]);
        }
        return out.append(']').toString();
    }
}
