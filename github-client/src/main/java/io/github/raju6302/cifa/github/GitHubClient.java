package io.github.raju6302.cifa.github;

import io.github.raju6302.cifa.github.auth.GitHubTokenProvider;
import io.github.raju6302.cifa.github.model.JobLog;
import io.github.raju6302.cifa.github.model.WorkflowJob;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** The GitHub Actions REST calls this project needs. */
public class GitHubClient {

    private static final int PAGE_SIZE = 100;

    private final RestClient api;
    private final GitHubTokenProvider tokens;
    private final int maxLogBytes;

    /** {@code api} must not follow redirects; log downloads handle the redirect themselves. */
    public GitHubClient(RestClient api, GitHubTokenProvider tokens, int maxLogBytes) {
        this.api = api;
        this.tokens = tokens;
        this.maxLogBytes = maxLogBytes;
    }

    /** All jobs of one attempt of a workflow run, following pagination. */
    public List<WorkflowJob> listJobs(String repo, long runId, int attempt, Long installationId) {
        String[] ownerAndName = split(repo);
        String token = tokens.token(installationId);
        List<WorkflowJob> jobs = new ArrayList<>();
        for (int page = 1; ; page++) {
            int pageNumber = page;
            JobsPage result = call(() -> api.get()
                    .uri("/repos/{owner}/{name}/actions/runs/{runId}/attempts/{attempt}/jobs?per_page={size}&page={page}",
                            ownerAndName[0], ownerAndName[1], runId, attempt, PAGE_SIZE, pageNumber)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(JobsPage.class));
            List<WorkflowJob> pageJobs = result == null || result.jobs() == null ? List.of() : result.jobs();
            jobs.addAll(pageJobs);
            if (pageJobs.size() < PAGE_SIZE || jobs.size() >= result.totalCount()) {
                return jobs;
            }
        }
    }

    /**
     * Downloads a job's plain-text log. GitHub answers with a redirect to a short-lived
     * signed URL; that second request is sent without our token so it never leaves GitHub's API.
     */
    public JobLog downloadJobLog(String repo, long jobId, Long installationId) {
        String[] ownerAndName = split(repo);
        String token = tokens.token(installationId);
        URI location = call(() -> api.get()
                .uri("/repos/{owner}/{name}/actions/jobs/{jobId}/logs", ownerAndName[0], ownerAndName[1], jobId)
                .header("Authorization", "Bearer " + token)
                .exchange((request, response) -> {
                    if (response.getStatusCode().is3xxRedirection() && response.getHeaders().getLocation() != null) {
                        return response.getHeaders().getLocation();
                    }
                    throw new GitHubApiException(response.getStatusCode().value(),
                            "Expected a redirect for job %d logs, got %s".formatted(jobId, response.getStatusCode()));
                }));
        return call(() -> api.get()
                .uri(location)
                .exchange((request, response) -> {
                    HttpStatusCode status = response.getStatusCode();
                    if (!status.is2xxSuccessful()) {
                        throw new GitHubApiException(status.value(), "Log download for job %d failed: %s".formatted(jobId, status));
                    }
                    TailBuffer tail = new TailBuffer(maxLogBytes);
                    try {
                        tail.readFully(response.getBody());
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return new JobLog(tail.text(), tail.totalBytes(), tail.truncated());
                }));
    }

    /** Splits "owner/name" into two path segments; as one URI variable the slash would be encoded. */
    private static String[] split(String repo) {
        if (repo == null || !repo.matches("[\\w.-]+/[\\w.-]+")) {
            throw new IllegalArgumentException("Repository must look like owner/name: " + repo);
        }
        return repo.split("/");
    }

    private static <T> T call(Supplier<T> request) {
        try {
            return request.get();
        } catch (RestClientResponseException e) {
            String message = e.getResponseBodyAsString();
            throw new GitHubApiException(e.getStatusCode().value(),
                    "GitHub API returned %s: %s".formatted(e.getStatusCode(), message.isBlank() ? e.getStatusText() : message));
        }
    }

    record JobsPage(int totalCount, List<WorkflowJob> jobs) {
    }
}
