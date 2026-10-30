package io.github.raju6302.cifa.worker.rag;

import java.util.List;
import org.springframework.ai.transformers.TransformersEmbeddingModel;

/**
 * Local sentence-embedding model (all-MiniLM-L6-v2, ONNX, 384 dimensions) via Spring AI.
 * Anthropic has no embeddings API, and running locally means code never leaves the
 * worker for indexing. The model (~90 MB) is downloaded and cached on first use, not at
 * startup, so the worker starts quickly; downloaded from Hugging Face by default because
 * Spring AI's own default URL is a Git LFS mirror that can be very slow.
 */
class OnnxEmbedder implements Embedder, AutoCloseable {

    private final String cacheDirectory;
    private final String modelUri;
    private final String tokenizerUri;
    private TransformersEmbeddingModel model;

    OnnxEmbedder(String cacheDirectory, String modelUri, String tokenizerUri) {
        this.cacheDirectory = cacheDirectory;
        this.modelUri = modelUri;
        this.tokenizerUri = tokenizerUri;
    }

    @Override
    public float[] embed(String text) {
        return model().embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        return model().embed(texts);
    }

    private synchronized TransformersEmbeddingModel model() {
        if (model == null) {
            TransformersEmbeddingModel loaded = new TransformersEmbeddingModel();
            loaded.setResourceCacheDirectory(cacheDirectory);
            loaded.setModelResource(modelUri);
            loaded.setTokenizerResource(tokenizerUri);
            try {
                loaded.afterPropertiesSet();
            } catch (Exception e) {
                throw new IllegalStateException("Could not load the embedding model from " + modelUri, e);
            }
            model = loaded;
        }
        return model;
    }

    @Override
    public synchronized void close() throws Exception {
        if (model != null) {
            model.close();
        }
    }
}
