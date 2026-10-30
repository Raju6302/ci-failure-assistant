package io.github.raju6302.cifa.worker.rag;

import io.github.raju6302.cifa.worker.diagnosis.FailureContext;
import io.github.raju6302.cifa.worker.diagnosis.JobToDiagnose;
import io.github.raju6302.cifa.worker.diagnosis.RunToDiagnose;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** RAG over Postgres + pgvector: code index, hybrid retrieval and failure memory. */
class PgRetrievalService implements RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(PgRetrievalService.class);

    private final CodeIndexer indexer;
    private final HybridRetriever retriever;
    private final FailureMemory memory;
    private final RagProperties properties;

    PgRetrievalService(CodeIndexer indexer, HybridRetriever retriever, FailureMemory memory, RagProperties properties) {
        this.indexer = indexer;
        this.retriever = retriever;
        this.memory = memory;
        this.properties = properties;
    }

    @Override
    public void prepare(RunToDiagnose run) {
        try {
            indexer.refresh(run.repositoryId(), run.repository(), run.installationId(), run.headSha());
        } catch (RuntimeException e) {
            // Diagnosis still works without code context, just less well.
            log.warn("Could not index {} at {}; diagnosing without retrieved code: {}",
                    run.repository(), run.headSha(), e.getMessage());
        }
    }

    @Override
    public List<FailureContext.CodeSnippet> relatedCode(RunToDiagnose run, JobToDiagnose job) {
        FailureContext.Analysis analysis = job.analysis();
        if (analysis == null) {
            return List.of();
        }
        return retriever.search(run.repositoryId(), analysis.summary() + "\n" + analysis.excerpt(),
                        analysis.codeReferences(), properties.topK())
                .stream()
                .map(s -> new FailureContext.CodeSnippet(s.path(), s.startLine(), s.endLine(), s.symbol(),
                        s.content().length() <= properties.maxSnippetChars() ? s.content()
                                : s.content().substring(0, properties.maxSnippetChars()) + "\n…"))
                .toList();
    }

    @Override
    public List<FailureContext.PastFailure> similarFailures(RunToDiagnose run, JobToDiagnose job) {
        FailureContext.Analysis analysis = job.analysis();
        if (analysis == null) {
            return List.of();
        }
        return memory.similar(run.repositoryId(), job.failedJobId(), analysis.fingerprint(), analysis.category(),
                analysis.summary());
    }

    @Override
    public void remember(RunToDiagnose run, JobToDiagnose job, String category, String rootCause) {
        FailureContext.Analysis analysis = job.analysis();
        memory.remember(run.repositoryId(), job.failedJobId(), analysis == null ? null : analysis.fingerprint(),
                category, analysis == null ? rootCause : analysis.summary(), rootCause);
    }
}
