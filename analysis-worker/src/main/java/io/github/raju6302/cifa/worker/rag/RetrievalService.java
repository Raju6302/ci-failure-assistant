package io.github.raju6302.cifa.worker.rag;

import io.github.raju6302.cifa.worker.diagnosis.FailureContext;
import io.github.raju6302.cifa.worker.diagnosis.JobToDiagnose;
import io.github.raju6302.cifa.worker.diagnosis.RunToDiagnose;
import java.util.List;

/** What the diagnosis stage needs from RAG. {@link #disabled()} when {@code cifa.rag.enabled=false}. */
public interface RetrievalService {

    /** Updates the code index to the run's commit. Failures are logged, not thrown. */
    void prepare(RunToDiagnose run);

    List<FailureContext.CodeSnippet> relatedCode(RunToDiagnose run, JobToDiagnose job);

    List<FailureContext.PastFailure> similarFailures(RunToDiagnose run, JobToDiagnose job);

    void remember(RunToDiagnose run, JobToDiagnose job, String category, String rootCause);

    static RetrievalService disabled() {
        return new RetrievalService() {
            @Override
            public void prepare(RunToDiagnose run) {
            }

            @Override
            public List<FailureContext.CodeSnippet> relatedCode(RunToDiagnose run, JobToDiagnose job) {
                return List.of();
            }

            @Override
            public List<FailureContext.PastFailure> similarFailures(RunToDiagnose run, JobToDiagnose job) {
                return List.of();
            }

            @Override
            public void remember(RunToDiagnose run, JobToDiagnose job, String category, String rootCause) {
            }
        };
    }
}
