package io.github.raju6302.cifa.worker.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Loads the real local embedding model (downloads ~90 MB on first run), so it's opt-in:
 * {@code ./mvnw -pl analysis-worker test -Dtest=OnnxEmbedderTest -Dcifa.test.onnx=true}.
 */
@EnabledIfSystemProperty(named = "cifa.test.onnx", matches = "true")
class OnnxEmbedderTest {

    @Test
    void producesVectorsThatFitTheSchemaAndCaptureMeaning() throws Exception {
        String model = RagProperties.HUGGING_FACE_MODEL;
        try (OnnxEmbedder embedder = new OnnxEmbedder(Path.of(System.getProperty("java.io.tmpdir"), "cifa-models").toString(),
                model + "/onnx/model.onnx", model + "/tokenizer.json")) {
            float[] error = embedder.embed("Could not resolve dependency org.apache.commons:commons-lang4");
            float[] related = embedder.embed("<dependency><groupId>org.apache.commons</groupId><artifactId>commons-lang4</artifactId></dependency>");
            float[] unrelated = embedder.embed("export function slugify(title) { return title.toLowerCase() }");

            assertThat(error).hasSize(Embedder.DIMENSIONS);
            assertThat(cosine(error, related)).isGreaterThan(cosine(error, unrelated));
        }
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / Math.sqrt(na * nb);
    }
}
