package io.github.raju6302.cifa.worker.rag;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * {@code cifa.rag.*}: code indexing and retrieval for the diagnosis agent.
 *
 * @param enabled           false = the agent gets no retrieved code or past failures
 * @param embedder          "onnx" (local all-MiniLM-L6-v2, the default) or "hashing" (no model
 *                          download; keyword-like, for tests and offline use)
 * @param maxFiles          files indexed per repository, to bound the first index
 * @param maxFileSize       larger files are skipped (usually generated or vendored)
 * @param maxArchiveSize    repositories whose archive is bigger are not indexed
 * @param chunkLines        target chunk size; chunks break at declarations when possible
 * @param topK              code snippets given to the agent
 * @param maxSnippetChars   cap per snippet in the prompt
 * @param memoryResults     similar past failures given to the agent
 * @param memoryMaxDistance cosine distance under which a past failure counts as similar
 * @param modelUri          ONNX embedding model (default: all-MiniLM-L6-v2 from Hugging Face)
 * @param tokenizerUri      its tokenizer
 */
@ConfigurationProperties("cifa.rag")
public record RagProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("onnx") String embedder,
        @DefaultValue("3000") int maxFiles,
        @DefaultValue("200KB") DataSize maxFileSize,
        @DefaultValue("100MB") DataSize maxArchiveSize,
        @DefaultValue("60") int chunkLines,
        @DefaultValue("6") int topK,
        @DefaultValue("2500") int maxSnippetChars,
        @DefaultValue("3") int memoryResults,
        @DefaultValue("0.35") double memoryMaxDistance,
        @DefaultValue(HUGGING_FACE_MODEL + "/onnx/model.onnx") String modelUri,
        @DefaultValue(HUGGING_FACE_MODEL + "/tokenizer.json") String tokenizerUri) {

    static final String HUGGING_FACE_MODEL = "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main";
}
