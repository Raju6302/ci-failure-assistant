package io.github.raju6302.cifa.mcp.tools;

import io.github.raju6302.cifa.github.model.Commit;
import io.github.raju6302.cifa.mcp.McpProperties;
import io.github.raju6302.cifa.mcp.text.LimitedText;
import io.github.raju6302.cifa.mcp.text.Lines;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
class GetCommitDiffTool implements AgentTool {

    private final RepositoryAllowlist repositories;
    private final CachedGitHub github;
    private final PathPolicy paths;
    private final int maxOutputChars;
    private final int maxPatchChars;

    GetCommitDiffTool(RepositoryAllowlist repositories, CachedGitHub github, PathPolicy paths, McpProperties properties) {
        this.repositories = repositories;
        this.github = github;
        this.paths = paths;
        this.maxOutputChars = properties.maxOutputChars();
        this.maxPatchChars = properties.maxPatchChars();
    }

    @Override
    public McpSchema.Tool definition() {
        return AgentTool.readOnly("get_commit_diff", "Show a commit's changes", """
                        Show what a commit changed: its message, author, parents and the unified diff of each \
                        changed file. Use it on the failed run's commit to find the change that most likely broke \
                        the build, and to compare the error with the lines that were edited. Each file's patch is \
                        capped at %d characters, and binary files have no patch. On a large commit, narrow the \
                        output with path_filter, and use get_file to read whole files."""
                        .formatted(maxPatchChars),
                InputSchema.create()
                        .repo()
                        .string("sha", true, "Commit sha (full or abbreviated), or a branch or tag name.")
                        .string("path_filter", false, "Only show files whose path contains this text "
                                + "(case-insensitive), e.g. 'src/main' or 'InvoiceService'.")
                        .build());
    }

    @Override
    public String call(ToolArguments arguments) {
        AllowedRepository repo = repositories.require(arguments.requiredString("repo"));
        String filter = arguments.optionalString("path_filter");
        Commit commit = github.commit(repo, arguments.requiredString("sha"));

        List<Commit.ChangedFile> allowed = commit.files().stream()
                .filter(file -> !paths.isDenied(file.filename())
                        && (file.previousFilename() == null || !paths.isDenied(file.previousFilename())))
                .toList();
        List<Commit.ChangedFile> shown = allowed.stream()
                .filter(file -> filter == null || file.filename().toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
                .toList();

        LimitedText out = new LimitedText(maxOutputChars);
        out.line("Commit %s in %s".formatted(commit.sha(), repo.fullName()));
        out.line("Author: %s, %s".formatted(commit.authorName(), commit.authoredAt()));
        out.line("Parents: " + (commit.parentShas().isEmpty() ? "none" : String.join(", ", commit.parentShas())));
        out.line("Message:");
        Lines.of(commit.message()).all().forEach(line -> out.line("    " + line));
        out.line("");
        out.line(summary(commit.files().size(), allowed.size(), shown.size(), filter));
        for (Commit.ChangedFile file : shown) {
            out.line("");
            if (!appendFile(out, file)) {
                break;
            }
        }
        return out.finish("Pass path_filter to see specific files, or read them with get_file.");
    }

    private static String summary(int total, int allowed, int shown, String filter) {
        StringBuilder summary = new StringBuilder("%d file(s) changed".formatted(total));
        if (allowed < total) {
            summary.append(", %d not shown because of this server's path policy".formatted(total - allowed));
        }
        if (filter != null) {
            summary.append(", %d matching path_filter \"%s\"".formatted(shown, filter));
        }
        return summary.append('.').toString();
    }

    /** @return false once the output limit is reached */
    private boolean appendFile(LimitedText out, Commit.ChangedFile file) {
        String renamed = file.previousFilename() == null ? "" : " from " + file.previousFilename();
        if (!out.line("--- %s (%s%s, +%d -%d)".formatted(
                file.filename(), file.status(), renamed, file.additions(), file.deletions()))) {
            return false;
        }
        if (file.patch() == null) {
            return out.line("(no patch: binary file, or the diff is too large for GitHub to show)");
        }
        LimitedText patch = new LimitedText(maxPatchChars);
        patch.lines(file.patch());
        String bounded = patch.finish("Read the file with get_file to see the rest of this change.");
        return out.lines(bounded);
    }
}
