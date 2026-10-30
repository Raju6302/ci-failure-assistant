package io.github.raju6302.cifa.github;

import io.github.raju6302.cifa.github.auth.GitHubTokenProvider;
import io.github.raju6302.cifa.github.model.CodeSearchResult;
import io.github.raju6302.cifa.github.model.Commit;
import io.github.raju6302.cifa.github.model.CommitSummary;
import io.github.raju6302.cifa.github.model.FileContent;
import io.github.raju6302.cifa.github.model.GitTree;
import io.github.raju6302.cifa.github.model.CheckRunAnnotation;
import io.github.raju6302.cifa.github.model.JobLog;
import io.github.raju6302.cifa.github.model.WorkflowJob;
import io.github.raju6302.cifa.github.model.WorkflowRun;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** The GitHub REST calls this project needs. */
public class GitHubClient {

    private static final int PAGE_SIZE = 100;
    private static final int SEARCH_PAGE_SIZE = 30;
    /** Code search returns text matches (the fragments around each hit) only with this media type. */
    private static final String TEXT_MATCH_JSON = "application/vnd.github.text-match+json";
    private static final int MAX_ANNOTATION_PAGES = 10;

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
        List<WorkflowJob> jobs = new ArrayList<>();
        for (int page = 1; ; page++) {
            JobsPage result = getJson(installationId, JobsPage.class,
                    "/repos/{owner}/{name}/actions/runs/{runId}/attempts/{attempt}/jobs?per_page={size}&page={page}",
                    ownerAndName[0], ownerAndName[1], runId, attempt, PAGE_SIZE, page);
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

    /**
     * Streams the repository's files at {@code ref} from GitHub's zip archive: one request
     * instead of one per file. Paths are repo-relative (the archive's top folder is removed).
     * Reading stops with an error once {@code maxBytes} of archive have been read.
     */
    public void readArchive(String repo, String ref, Long installationId, long maxBytes, Consumer<ArchiveFile> visitor) {
        String[] ownerAndName = split(repo);
        String token = tokens.token(installationId);
        URI location = call(() -> api.get()
                .uri("/repos/{owner}/{name}/zipball/{ref}", ownerAndName[0], ownerAndName[1], ref)
                .header("Authorization", "Bearer " + token)
                .exchange((request, response) -> {
                    if (response.getStatusCode().is3xxRedirection() && response.getHeaders().getLocation() != null) {
                        return response.getHeaders().getLocation();
                    }
                    throw new GitHubApiException(response.getStatusCode().value(),
                            "Expected a redirect for the %s archive at %s, got %s".formatted(repo, ref, response.getStatusCode()));
                }));
        call(() -> api.get()
                .uri(location)
                .exchange((request, response) -> {
                    if (!response.getStatusCode().is2xxSuccessful()) {
                        throw new GitHubApiException(response.getStatusCode().value(),
                                "Archive download for %s failed: %s".formatted(repo, response.getStatusCode()));
                    }
                    try (ZipInputStream zip = new ZipInputStream(new LimitedInputStream(response.getBody(), maxBytes))) {
                        for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                            int slash = entry.getName().indexOf('/');
                            if (!entry.isDirectory() && slash >= 0 && slash < entry.getName().length() - 1) {
                                visitor.accept(new ArchiveFile(entry.getName().substring(slash + 1), zip.readAllBytes()));
                            }
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return null;
                }));
    }

    /** A file from a repository archive. */
    public record ArchiveFile(String path, byte[] content) {
    }

    /** A workflow run, mainly for its workflow file path and head commit. */
    public WorkflowRun getWorkflowRun(String repo, long runId, Long installationId) {
        String[] ownerAndName = split(repo);
        return getJson(installationId, WorkflowRun.class, "/repos/{owner}/{name}/actions/runs/{runId}",
                ownerAndName[0], ownerAndName[1], runId);
    }

    /** A commit with its message, parents and changed files (first 300) including their patches. */
    public Commit getCommit(String repo, String ref, Long installationId) {
        String[] ownerAndName = split(repo);
        CommitResponse response = getJson(installationId, CommitResponse.class, "/repos/{owner}/{name}/commits/{ref}",
                ownerAndName[0], ownerAndName[1], requireRef(ref));
        CommitSummary summary = response.toSummary();
        List<String> parents = response.parents() == null ? List.of()
                : response.parents().stream().map(ShaRef::sha).toList();
        return new Commit(summary.sha(), summary.message(), summary.authorName(), summary.authoredAt(), parents,
                response.files() == null ? List.of() : response.files());
    }

    /** The latest commits on a branch (or any ref), newest first. */
    public List<CommitSummary> listCommits(String repo, String branch, int limit, Long installationId) {
        String[] ownerAndName = split(repo);
        CommitResponse[] response = getJson(installationId, CommitResponse[].class,
                "/repos/{owner}/{name}/commits?sha={branch}&per_page={limit}",
                ownerAndName[0], ownerAndName[1], requireRef(branch), Math.clamp(limit, 1, PAGE_SIZE));
        return response == null ? List.of() : Arrays.stream(response).map(CommitResponse::toSummary).toList();
    }

    /**
     * A file's content at a ref (branch, tag or sha). Text is only decoded for files up to
     * 1 MB that look like UTF-8; others come back with {@code text == null}.
     *
     * @throws IllegalArgumentException if the path is a directory, symlink or submodule
     */
    public FileContent getFileContent(String repo, String path, String ref, Long installationId) {
        String[] ownerAndName = split(repo);
        List<String> segments = pathSegments(path);
        // One URI variable per segment: encoded on its own, so "/" stays a separator and
        // nothing in a file name can change the request.
        StringBuilder template = new StringBuilder("/repos/{owner}/{name}/contents");
        List<Object> variables = new ArrayList<>(List.of(ownerAndName[0], ownerAndName[1]));
        for (int i = 0; i < segments.size(); i++) {
            template.append("/{s").append(i).append('}');
            variables.add(segments.get(i));
        }
        template.append("?ref={ref}");
        variables.add(requireRef(ref));
        JsonNode node = getJson(installationId, JsonNode.class, template.toString(), variables.toArray());
        if (node == null || node.isArray()) {
            throw new IllegalArgumentException(path + " is a directory, not a file");
        }
        ContentResponse content = GitHubJson.mapper().treeToValue(node, ContentResponse.class);
        if (!"file".equals(content.type())) {
            throw new IllegalArgumentException("%s is a %s, not a regular file".formatted(path, content.type()));
        }
        String text = content.size() <= FileContent.MAX_TEXT_BYTES && "base64".equals(content.encoding())
                && content.content() != null ? FileText.decode(content.content()) : null;
        return new FileContent(content.path(), content.sha(), content.size(), text);
    }

    /**
     * Code search within one repository. GitHub only indexes the default branch, so hits
     * may not match the code at another ref. Results from any other repository are dropped,
     * in case the query tried to widen the search with its own {@code repo:} qualifier.
     */
    public CodeSearchResult searchCode(String repo, String query, Long installationId) {
        split(repo);
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("Search query must not be empty");
        }
        SearchResponse response = getJson(installationId, TEXT_MATCH_JSON, SearchResponse.class,
                "/search/code?q={query}&per_page={size}", query.strip() + " repo:" + repo, SEARCH_PAGE_SIZE);
        if (response == null || response.items() == null) {
            return new CodeSearchResult(0, false, List.of());
        }
        List<CodeSearchResult.Match> matches = response.items().stream()
                .filter(item -> item.repository() != null && repo.equalsIgnoreCase(item.repository().fullName()))
                .map(SearchItem::toMatch)
                .toList();
        return new CodeSearchResult(response.totalCount(), response.incompleteResults(), matches);
    }

    /** Every entry under a ref, recursively. Check {@link GitTree#truncated()} on big repos. */
    public GitTree getTree(String repo, String ref, Long installationId) {
        String[] ownerAndName = split(repo);
        return getJson(installationId, GitTree.class, "/repos/{owner}/{name}/git/trees/{ref}?recursive=1",
                ownerAndName[0], ownerAndName[1], requireRef(ref));
    }

    /**
     * Annotations of a job's check run (a job's id is also its check run's id). This is the
     * only place GitHub says a {@code cancelled} job actually hit its time limit. Stops after
     * {@value #MAX_ANNOTATION_PAGES} pages so a noisy test reporter can't exhaust memory.
     */
    public List<CheckRunAnnotation> listAnnotations(String repo, long checkRunId, Long installationId) {
        String[] ownerAndName = split(repo);
        String token = tokens.token(installationId);
        List<CheckRunAnnotation> annotations = new ArrayList<>();
        for (int page = 1; page <= MAX_ANNOTATION_PAGES; page++) {
            int pageNumber = page;
            CheckRunAnnotation[] result = call(() -> api.get()
                    .uri("/repos/{owner}/{name}/check-runs/{id}/annotations?per_page={size}&page={page}",
                            ownerAndName[0], ownerAndName[1], checkRunId, PAGE_SIZE, pageNumber)
                    .header("Authorization", "Bearer " + token)
                    .retrieve()
                    .body(CheckRunAnnotation[].class));
            List<CheckRunAnnotation> pageAnnotations = result == null ? List.of() : List.of(result);
            annotations.addAll(pageAnnotations);
            if (pageAnnotations.size() < PAGE_SIZE) {
                break;
            }
        }
        return annotations;
    }

    /** Splits "owner/name" into two path segments; as one URI variable the slash would be encoded. */
    private static String[] split(String repo) {
        if (repo == null || !repo.matches("[\\w.-]+/[\\w.-]+")) {
            throw new IllegalArgumentException("Repository must look like owner/name: " + repo);
        }
        return repo.split("/");
    }

    private static String requireRef(String ref) {
        if (ref == null || ref.isBlank()) {
            throw new IllegalArgumentException("A branch, tag or commit sha is required");
        }
        return ref.strip();
    }

    /** Relative path segments; "." and ".." are refused so a path can't climb out of the contents API. */
    private static List<String> pathSegments(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("A file path is required");
        }
        List<String> segments = List.of(path.strip().replaceFirst("^/+", "").split("/+"));
        if (segments.stream().anyMatch(s -> s.isEmpty() || s.equals(".") || s.equals(".."))) {
            throw new IllegalArgumentException("Path must be relative to the repository root, without . or ..: " + path);
        }
        return segments;
    }

    private <T> T getJson(Long installationId, Class<T> type, String uri, Object... variables) {
        return getJson(installationId, "application/vnd.github+json", type, uri, variables);
    }

    private <T> T getJson(Long installationId, String accept, Class<T> type, String uri, Object... variables) {
        String token = tokens.token(installationId);
        return call(() -> api.get()
                .uri(uri, variables)
                .header("Authorization", "Bearer " + token)
                .header("Accept", accept)
                .retrieve()
                .body(type));
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

    /** The shape shared by the single-commit and commit-list endpoints. */
    record CommitResponse(String sha, CommitDetails commit, List<ShaRef> parents, List<Commit.ChangedFile> files) {

        CommitSummary toSummary() {
            Person author = commit == null ? null : commit.author();
            return new CommitSummary(sha, commit == null ? null : commit.message(),
                    author == null ? null : author.name(), author == null ? null : author.date());
        }
    }

    record CommitDetails(String message, Person author) {
    }

    record Person(String name, Instant date) {
    }

    record ShaRef(String sha) {
    }

    record ContentResponse(String type, String path, String sha, long size, String encoding, String content) {
    }

    record SearchResponse(int totalCount, boolean incompleteResults, List<SearchItem> items) {
    }

    record SearchItem(String path, String sha, SearchRepository repository, List<TextMatch> textMatches) {

        CodeSearchResult.Match toMatch() {
            List<String> fragments = textMatches == null ? List.of()
                    : textMatches.stream().map(TextMatch::fragment).toList();
            return new CodeSearchResult.Match(path, sha, fragments);
        }
    }

    record SearchRepository(String fullName) {
    }

    record TextMatch(String fragment) {
    }
}
