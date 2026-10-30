package io.github.raju6302.cifa.mcp.tools;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.raju6302.cifa.github.GitHubClient;
import io.github.raju6302.cifa.github.model.CodeSearchResult;
import io.github.raju6302.cifa.github.model.Commit;
import io.github.raju6302.cifa.github.model.CommitSummary;
import io.github.raju6302.cifa.github.model.FileContent;
import io.github.raju6302.cifa.github.model.WorkflowRun;
import io.github.raju6302.cifa.mcp.McpProperties;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * The GitHub reads the tools make, with caching for answers that can't change. An agent
 * often reads the same file or commit several times in one diagnosis, and GitHub's rate
 * limit is shared with the worker.
 *
 * <p>Only lookups by full commit sha are cached: a sha names immutable content, while a
 * branch moves. A workflow run's file path and head sha are fixed once the run exists.
 */
@Component
class CachedGitHub {

    private static final Pattern FULL_SHA = Pattern.compile("[0-9a-fA-F]{40}");

    private final GitHubClient github;
    private final Cache<RefKey, Commit> commits;
    private final Cache<FileKey, FileContent> files;
    private final Cache<RunKey, WorkflowRun> runs;

    CachedGitHub(GitHubClient github, McpProperties properties) {
        this.github = github;
        // Entries are weighed in characters; a Java string takes up to 2 bytes per character.
        long maxChars = properties.cache().maxSize().toBytes() / 2;
        this.commits = Caffeine.newBuilder()
                .expireAfterWrite(properties.cache().ttl())
                .maximumWeight(maxChars)
                .weigher((RefKey key, Commit commit) -> weight(commit))
                .build();
        this.files = Caffeine.newBuilder()
                .expireAfterWrite(properties.cache().ttl())
                .maximumWeight(maxChars)
                .weigher((FileKey key, FileContent file) -> 100 + (file.text() == null ? 0 : file.text().length()))
                .build();
        this.runs = Caffeine.newBuilder()
                .expireAfterWrite(properties.cache().ttl())
                .maximumSize(1_000)
                .build();
    }

    Commit commit(AllowedRepository repo, String sha) {
        if (!isFullSha(sha)) {
            return github.getCommit(repo.fullName(), sha, repo.installationId());
        }
        return commits.get(new RefKey(repo.fullName(), sha.toLowerCase()),
                key -> github.getCommit(repo.fullName(), sha, repo.installationId()));
    }

    FileContent file(AllowedRepository repo, String path, String ref) {
        if (!isFullSha(ref)) {
            return github.getFileContent(repo.fullName(), path, ref, repo.installationId());
        }
        return files.get(new FileKey(repo.fullName(), path, ref.toLowerCase()),
                key -> github.getFileContent(repo.fullName(), path, ref, repo.installationId()));
    }

    WorkflowRun workflowRun(AllowedRepository repo, long runId) {
        return runs.get(new RunKey(repo.fullName(), runId),
                key -> github.getWorkflowRun(repo.fullName(), runId, repo.installationId()));
    }

    List<CommitSummary> recentCommits(AllowedRepository repo, String branch, int limit) {
        return github.listCommits(repo.fullName(), branch, limit, repo.installationId());
    }

    CodeSearchResult searchCode(AllowedRepository repo, String query) {
        return github.searchCode(repo.fullName(), query, repo.installationId());
    }

    static boolean isFullSha(String ref) {
        return ref != null && FULL_SHA.matcher(ref).matches();
    }

    /** Roughly the characters held, which is what dominates memory. */
    private static int weight(Commit commit) {
        long chars = 100 + (commit.message() == null ? 0 : commit.message().length());
        for (Commit.ChangedFile file : commit.files()) {
            chars += 100 + (file.patch() == null ? 0 : file.patch().length());
        }
        return (int) Math.min(chars, Integer.MAX_VALUE);
    }

    private record RefKey(String repo, String sha) {
    }

    private record FileKey(String repo, String path, String sha) {
    }

    private record RunKey(String repo, long runId) {
    }
}
