package io.github.raju6302.cifa.worker.rag;

import io.github.raju6302.cifa.github.GitHubClient;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
class RagConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Embedder embedder(RagProperties properties,
                      @Value("${cifa.rag.model-cache-dir:${java.io.tmpdir}/cifa-models}") Path cacheDirectory) {
        // With RAG off nothing is embedded, so don't download the model.
        if (!properties.enabled() || "hashing".equals(properties.embedder())) {
            return new HashingEmbedder();
        }
        return new OnnxEmbedder(cacheDirectory.toString(), properties.modelUri(), properties.tokenizerUri());
    }

    @Bean
    @ConditionalOnMissingBean
    RepositorySource repositorySource(GitHubClient github, RagProperties properties) {
        return new GitHubArchiveSource(github, properties.maxArchiveSize().toBytes());
    }

    @Bean
    RetrievalService retrievalService(RagProperties properties, JdbcClient jdbc, TransactionTemplate transactions,
                                      RepositorySource source, Embedder embedder) {
        if (!properties.enabled()) {
            return RetrievalService.disabled();
        }
        return new PgRetrievalService(new CodeIndexer(jdbc, transactions, source, embedder, properties),
                new HybridRetriever(jdbc, embedder), new FailureMemory(jdbc, embedder, properties), properties);
    }
}
