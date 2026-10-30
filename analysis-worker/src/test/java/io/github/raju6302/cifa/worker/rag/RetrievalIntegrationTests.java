package io.github.raju6302.cifa.worker.rag;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.raju6302.cifa.worker.TestcontainersConfiguration;
import io.github.raju6302.cifa.worker.diagnosis.FailureContext;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Indexes the repository's own sandbox projects and checks that retrieval puts the file at
 * fault near the top for real error messages from their CI logs.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class RetrievalIntegrationTests {

    static final Path SANDBOX = Path.of("..", "sandbox-repos").toAbsolutePath().normalize();

    @Autowired
    JdbcClient jdbc;

    @Autowired
    TransactionTemplate transactions;

    RagProperties properties = new RagProperties(true, "hashing", 3000, org.springframework.util.unit.DataSize.ofKilobytes(200),
            org.springframework.util.unit.DataSize.ofMegabytes(100), 60, 6, 2500, 3, 0.35);
    Embedder embedder = new HashingEmbedder();
    long repositoryId;

    @BeforeEach
    void setUp() {
        jdbc.sql("TRUNCATE code_chunk, code_file, failure_memory, failed_job, workflow_run, repository RESTART IDENTITY CASCADE").update();
        repositoryId = jdbc.sql("INSERT INTO repository (github_id, full_name) VALUES (1, 'octo/sandbox') RETURNING id")
                .query(Long.class).single();
    }

    @Test
    void indexesOnlyChangedFilesOnRefresh() {
        CodeIndexer indexer = indexer();

        CodeIndexer.IndexStats first = indexer.refresh(repositoryId, "octo/sandbox", null, "commit-1");
        CodeIndexer.IndexStats same = indexer.refresh(repositoryId, "octo/sandbox", null, "commit-1");
        CodeIndexer.IndexStats next = indexer.refresh(repositoryId, "octo/sandbox", null, "commit-2");

        assertThat(first.filesIndexed()).isGreaterThanOrEqualTo(10);
        assertThat(first.chunks()).isGreaterThanOrEqualTo(first.filesIndexed());
        assertThat(same).isEqualTo(CodeIndexer.IndexStats.UP_TO_DATE);
        assertThat(next.filesSeen()).isEqualTo(first.filesSeen());
        assertThat(next.filesIndexed()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM code_file WHERE path LIKE '%README.md'").query(Long.class).single())
                .as("docs are not indexed").isZero();
    }

    @Test
    void findsTheFileAtFaultForRealErrors() {
        indexer().refresh(repositoryId, "octo/sandbox", null, "commit-1");
        HybridRetriever retriever = new HybridRetriever(jdbc, embedder);

        assertTopHit(retriever, """
                        [ERROR] /home/runner/work/x/x/sandbox-repos/java-compile-error/src/main/java/shop/InvoiceService.java:[9,34] cannot find symbol
                          symbol:   method price()
                          location: variable item of type shop.Order.LineItem""",
                List.of(), "java-compile-error/src/main/java/shop/InvoiceService.java");
        assertTopHit(retriever, "FAILED test_durations.py::test_hours_and_minutes - assert 31 == 5400 where 31 = parse_duration('1h30m')",
                List.of(), "python-test-failure/durations.py", "python-test-failure/test_durations.py");
        assertTopHit(retriever, "Could not find artifact org.apache.commons:commons-lang4:jar:3.17.0 in central",
                List.of(), "java-missing-dependency/pom.xml");
    }

    @Test
    void filesTheLogPointsToComeFirst() {
        indexer().refresh(repositoryId, "octo/sandbox", null, "commit-1");
        HybridRetriever retriever = new HybridRetriever(jdbc, embedder);

        List<HybridRetriever.Snippet> results = retriever.search(repositoryId, "AssertionError: expected values to be strictly equal",
                List.of(new FailureContext.CodeReference("node-test-failure/slugify.test.js", 10)), 3);

        assertThat(results.getFirst().path()).isEqualTo("node-test-failure/slugify.test.js");
    }

    @Test
    void remembersAndRecognizesRecurringFailures() {
        long jobA = insertJob(1, "sha-a");
        long jobB = insertJob(2, "sha-b");
        FailureMemory memory = new FailureMemory(jdbc, embedder, properties);
        memory.remember(repositoryId, jobA, "fp-1", "TEST_FAILURE", "PriceCalculatorTest.appliesPercentageDiscount failed",
                "applyDiscount divides by 1 instead of 100");

        List<FailureContext.PastFailure> exact = memory.similar(repositoryId, jobB, "fp-1", "TEST_FAILURE", "anything");
        List<FailureContext.PastFailure> fuzzy = memory.similar(repositoryId, jobB, "fp-other", "TEST_FAILURE",
                "PriceCalculatorTest.appliesPercentageDiscount failed");
        List<FailureContext.PastFailure> none = memory.similar(repositoryId, jobB, "fp-other", "DEPENDENCY",
                "Could not resolve commons-lang4");

        assertThat(exact).singleElement().satisfies(past -> {
            assertThat(past.sameFingerprint()).isTrue();
            assertThat(past.headSha()).isEqualTo("sha-a");
        });
        assertThat(fuzzy).singleElement().satisfies(past -> assertThat(past.sameFingerprint()).isFalse());
        assertThat(none).isEmpty();
        assertThat(memory.similar(repositoryId, jobA, "fp-1", "TEST_FAILURE", "x")).as("never itself").isEmpty();
    }

    private void assertTopHit(HybridRetriever retriever, String error, List<FailureContext.CodeReference> refs,
                              String... acceptable) {
        List<HybridRetriever.Snippet> results = retriever.search(repositoryId, error, refs, 3);
        assertThat(results).as("top results for: %s", error)
                .extracting(HybridRetriever.Snippet::path)
                .containsAnyOf(acceptable);
    }

    private CodeIndexer indexer() {
        return new CodeIndexer(jdbc, transactions, new LocalDirectorySource(SANDBOX), embedder, properties);
    }

    private long insertJob(int n, String sha) {
        long runId = jdbc.sql("""
                        INSERT INTO workflow_run (repository_id, github_run_id, run_attempt, run_number, workflow_name,
                                                  event, head_sha, conclusion, html_url)
                        VALUES (:repo, :n, 1, :n, 'CI', 'push', :sha, 'failure', 'https://example.com') RETURNING id""")
                .param("repo", repositoryId).param("n", n).param("sha", sha).query(Long.class).single();
        return jdbc.sql("INSERT INTO failed_job (workflow_run_id, github_job_id, name, conclusion) VALUES (:run, :n, 'test', 'failure') RETURNING id")
                .param("run", runId).param("n", n).query(Long.class).single();
    }
}
