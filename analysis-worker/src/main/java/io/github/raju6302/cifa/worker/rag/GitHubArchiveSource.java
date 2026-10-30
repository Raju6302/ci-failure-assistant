package io.github.raju6302.cifa.worker.rag;

import io.github.raju6302.cifa.github.GitHubClient;
import java.util.function.Consumer;

/** Reads a repository at a commit from GitHub's zip archive. */
class GitHubArchiveSource implements RepositorySource {

    private final GitHubClient github;
    private final long maxArchiveBytes;

    GitHubArchiveSource(GitHubClient github, long maxArchiveBytes) {
        this.github = github;
        this.maxArchiveBytes = maxArchiveBytes;
    }

    @Override
    public void forEachFile(String repository, String ref, Long installationId, Consumer<SourceFile> visitor) {
        github.readArchive(repository, ref, installationId, maxArchiveBytes,
                file -> visitor.accept(new SourceFile(file.path(), file.content())));
    }
}
