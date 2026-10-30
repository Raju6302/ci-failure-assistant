package io.github.raju6302.cifa.mcp.tools;

import io.github.raju6302.cifa.github.model.CommitSummary;
import io.github.raju6302.cifa.mcp.McpProperties;
import io.github.raju6302.cifa.mcp.text.LimitedText;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class ListRecentCommitsTool implements AgentTool {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 30;

    private final RepositoryAllowlist repositories;
    private final CachedGitHub github;
    private final int maxOutputChars;

    ListRecentCommitsTool(RepositoryAllowlist repositories, CachedGitHub github, McpProperties properties) {
        this.repositories = repositories;
        this.github = github;
        this.maxOutputChars = properties.maxOutputChars();
    }

    @Override
    public McpSchema.Tool definition() {
        return AgentTool.readOnly("list_recent_commits", "List recent commits", """
                        List the latest commits on a branch, newest first: full sha, author, date and the first \
                        line of the message. Use it to see what changed shortly before a failure (for example when \
                        the failing commit itself looks harmless) and to pick commits to inspect with \
                        get_commit_diff.""",
                InputSchema.create()
                        .repo()
                        .string("branch", true, "Branch name (or a tag or sha to list history from), e.g. main.")
                        .integer("limit", false, 1, (long) MAX_LIMIT,
                                "How many commits to return. Default %d, at most %d.".formatted(DEFAULT_LIMIT, MAX_LIMIT))
                        .build());
    }

    @Override
    public String call(ToolArguments arguments) {
        AllowedRepository repo = repositories.require(arguments.requiredString("repo"));
        String branch = arguments.requiredString("branch");
        Integer requested = arguments.optionalInt("limit");
        int limit = requested == null ? DEFAULT_LIMIT : Math.clamp(requested, 1, MAX_LIMIT);
        List<CommitSummary> commits = github.recentCommits(repo, branch, limit);

        LimitedText out = new LimitedText(maxOutputChars);
        out.line("Latest %d commit(s) on %s in %s, newest first:".formatted(commits.size(), branch, repo.fullName()));
        for (CommitSummary commit : commits) {
            out.line("%s  %s  %s: %s".formatted(commit.sha(), commit.authoredAt(), commit.authorName(), commit.subject()));
        }
        return out.finish("Ask for fewer commits with limit.");
    }
}
