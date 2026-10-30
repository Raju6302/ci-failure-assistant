package io.github.raju6302.cifa.worker.logs;

import io.github.raju6302.cifa.github.GitHubJson;
import io.github.raju6302.cifa.github.model.CheckRunAnnotation;
import io.github.raju6302.cifa.github.model.WorkflowJob;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Loads the real GitHub Actions logs in {@code src/test/resources/logs} (see the README there). */
public final class Fixtures {

    private Fixtures() {
    }

    public static String log(String scenario) {
        return resource(scenario + ".log");
    }

    public static WorkflowJob job(String scenario) {
        return GitHubJson.mapper().readValue(resource(scenario + ".job.json"), WorkflowJob.class);
    }

    public static List<CheckRunAnnotation> annotations(String scenario) {
        return List.of(GitHubJson.mapper().readValue(resource(scenario + ".annotations.json"), CheckRunAnnotation[].class));
    }

    /** The evidence ingestion would hand to the analyzer for this scenario. */
    public static JobEvidence evidence(String scenario) {
        WorkflowJob job = job(scenario);
        List<String> failedSteps = job.failedStepNames().isEmpty()
                ? job.steps().stream().filter(step -> "cancelled".equals(step.conclusion())).map(WorkflowJob.Step::name).toList()
                : job.failedStepNames();
        return new JobEvidence(job.conclusion(), failedSteps, log(scenario), annotations(scenario));
    }

    private static String resource(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/logs/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
