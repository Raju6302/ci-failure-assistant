package io.github.raju6302.cifa.worker.rag;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashingEmbedderTest {

    HashingEmbedder embedder = new HashingEmbedder();

    @Test
    void producesUnitVectorsOfTheRightSize() {
        float[] vector = embedder.embed("PriceCalculator.applyDiscount");

        assertThat(vector).hasSize(Embedder.DIMENSIONS);
        double norm = 0;
        for (float v : vector) {
            norm += v * v;
        }
        assertThat(norm).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(embedder.embed("")).hasSize(Embedder.DIMENSIONS);
    }

    @Test
    void sharedIdentifiersMeanHigherSimilarity() {
        float[] error = embedder.embed("expected <90.0> but was <-900.0> at PriceCalculatorTest.appliesPercentageDiscount");
        float[] related = embedder.embed("class PriceCalculator { double applyDiscount(double price, double percent) }");
        float[] unrelated = embedder.embed("function slugify(title) { return title.trim().toLowerCase() }");

        assertThat(cosine(error, related)).isGreaterThan(cosine(error, unrelated));
    }

    @Test
    void writesPgvectorLiterals() {
        assertThat(Embedder.toVectorLiteral(new float[] {0.5f, -1f})).isEqualTo("[0.5,-1.0]");
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return dot;
    }
}
