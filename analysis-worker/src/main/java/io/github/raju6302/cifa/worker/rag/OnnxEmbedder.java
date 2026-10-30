package io.github.raju6302.cifa.worker.rag;

import java.util.List;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

/**
 * Local sentence-embedding model (all-MiniLM-L6-v2, ONNX, 384 dimensions) via Spring AI.
 * Anthropic has no embeddings API, and running locally means code never leaves the
 * worker for indexing. The model (~90 MB) is downloaded and cached on first start.
 * Downloaded from Hugging Face by default; Spring AI's own default URL is a Git LFS
 * mirror that can be very slow.
 */
class OnnxEmbedder implements Embedder, AutoCloseable {

    private final TransformersEmbeddingModel model;

    OnnxEmbedder(String cacheDirectory, String modelUri, String tokenizerUri) throws Exception {
        model = new TransformersEmbeddingModel();
        model.setResourceCacheDirectory(cacheDirectory);
        model.setModelResource(modelUri);
        model.setTokenizerResource(tokenizerUri);
        model.afterPropertiesSet();
    }

    @Override
    public float[] embed(String text) {
        return model.embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return model.embed(texts);
    }

    @Override
    public void close() throws Exception {
        model.close();
    }
}
