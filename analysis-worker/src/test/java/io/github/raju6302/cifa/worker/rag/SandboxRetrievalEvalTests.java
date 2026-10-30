package io.github.raju6302.cifa.worker.rag;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.raju6302.cifa.worker.TestcontainersConfiguration;
import io.github.raju6302.cifa.worker.diagnosis.FailureContext;
import io.github.raju6302.cifa.worker.logs.FailureAnalysis;
import io.github.raju6302.cifa.worker.logs.FailureAnalyzer;
import io.github.raju6302.cifa.worker.logs.Fixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Retrieval eval: for each sandbox scenario's real CI log, run the Phase 2 analyzer and
 * the hybrid retriever over this whole repository, and check the file at fault (from
 * {@code evals/sandbox-expected.json}) is among the snippets given to the agent.
 * Uses the hashing embedder, so it measures the keyword + log-reference signals;
 * the ONNX model only adds to that.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SandboxRetrievalEvalTests {

    static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    RagProperties properties = new RagProperties(true, "hashing", 3000, DataSize.ofKilobytes(200),
            DataSize.ofMegabytes(100), 60, 6, 2500, 3, 0.35, "unused", "unused");
    Embedder embedder = new HashingEmbedder();
    long repositoryId;

    @BeforeAll
    void indexThisRepository() {
        jdbc.sql("TRUNCATE code_chunk, code_file, failure_memory, failed_job, workflow_run, repository RESTART IDENTITY CASCADE").update();
        repositoryId = jdbc.sql("INSERT INTO repository (github_id, full_name) VALUES (7, 'Raju6302/ci-failure-assistant') RETURNING id")
                .query(Long.class).single();
        new CodeIndexer(jdbc, transactions, new LocalDirectorySource(REPO_ROOT), embedder, properties)
                .refresh(repositoryId, "Raju6302/ci-failure-assistant", null, "HEAD");
    }

    @Test
    void neverIndexesTheExpectedAnswers() {
        assertThat(jdbc.sql("SELECT count(*) FROM code_file WHERE path LIKE 'evals/%'").query(Long.class).single()).isZero();
    }

    @Test
    void retrievesTheFileAtFault() throws Exception {
        JsonNode expected = JsonMapper.builder().build()
                .readTree(Files.readString(REPO_ROOT.resolve("evals/sandbox-expected.json"))).get("scenarios");
        HybridRetriever retriever = new HybridRetriever(jdbc, embedder);
        FailureAnalyzer analyzer = new FailureAnalyzer();

        Map<String, Integer> rankOfCulprit = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> scenario : expected.properties()) {
            String culprit = scenario.getValue().get("culprit").get("file").asString();
            FailureAnalysis analysis = analyzer.analyze(Fixtures.evidence(scenario.getKey()));
            List<FailureContext.CodeReference> references = analysis.codeReferences().stream()
                    .map(r -> new FailureContext.CodeReference(r.path(), r.line())).toList();
            List<String> paths = new ArrayList<>(retriever.search(repositoryId, analysis.summary() + "\n" + analysis.excerpt(),
                    references, properties.topK()).stream().map(HybridRetriever.Snippet::path).toList());
            rankOfCulprit.put(scenario.getKey(), paths.indexOf(culprit) + 1); // 0 = not retrieved
        }
        System.out.println("Rank of the file at fault in the top " + properties.topK() + " (0 = missed): " + rankOfCulprit);

        assertThat(rankOfCulprit.values()).as("every culprit is retrieved: %s", rankOfCulprit).doesNotContain(0);
        assertThat(rankOfCulprit.values()).as("and in the top 3: %s", rankOfCulprit).allMatch(rank -> rank <= 3);
    }
}
