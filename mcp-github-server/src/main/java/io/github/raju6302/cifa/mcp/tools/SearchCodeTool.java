package io.github.raju6302.cifa.mcp.tools;

import io.github.raju6302.cifa.github.model.CodeSearchResult;
import io.github.raju6302.cifa.mcp.McpProperties;
import io.github.raju6302.cifa.mcp.text.LimitedText;
import io.github.raju6302.cifa.mcp.text.Lines;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class SearchCodeTool implements AgentTool {

    /** GitHub rejects longer queries. */
    static final int MAX_QUERY_LENGTH = 200;
    /** The repository is fixed by the {@code repo} argument; a query must not widen or move the search. */
    private static final Pattern SCOPE_QUALIFIER = Pattern.compile("(?i)(^|[\\s(])-?(repo|org|user|owner)\\s*:");

    private final RepositoryAllowlist repositories;
    private final CachedGitHub github;
    private final PathPolicy paths;
    private final int maxOutputChars;

    SearchCodeTool(RepositoryAllowlist repositories, CachedGitHub github, PathPolicy paths, McpProperties properties) {
        this.repositories = repositories;
        this.github = github;
        this.paths = paths;
        this.maxOutputChars = properties.maxOutputChars();
    }

    @Override
    public McpSchema.Tool definition() {
        return AgentTool.readOnly("search_code", "Search code", """
                        Search a repository's code with GitHub code search and get the matching file paths with \
                        the text fragments around each match. Use it to find where a class, method, config key or \
                        error message is defined or used when the log doesn't say which file it is in. GitHub \
                        only indexes the default branch, so results can differ from the failed commit: confirm \
                        with get_file at the run's sha. Returns at most 30 files.""",
                InputSchema.create()
                        .repo()
                        .string("query", true, "Search terms, e.g. 'InvoiceService' or '\"connection refused\"'. "
                                + "Qualifiers such as path:src/main, language:java or extension:yml are allowed; "
                                + "repo:, org: and user: are not (the repository is already set).")
                        .build());
    }

    @Override
    public String call(ToolArguments arguments) {
        AllowedRepository repo = repositories.require(arguments.requiredString("repo"));
        String query = arguments.requiredString("query");
        if (query.length() > MAX_QUERY_LENGTH) {
            throw new ToolException("The query is too long; keep it under %d characters.".formatted(MAX_QUERY_LENGTH));
        }
        if (SCOPE_QUALIFIER.matcher(query).find()) {
            throw new ToolException("Don't use repo:, org: or user: in the query; the repository is set by 'repo'.");
        }
        CodeSearchResult result = github.searchCode(repo, query);
        List<CodeSearchResult.Match> allowed = result.matches().stream()
                .filter(match -> !paths.isDenied(match.path()))
                .toList();

        LimitedText out = new LimitedText(maxOutputChars);
        out.line("Code search for %s in %s (default branch only): %d file(s) match%s.".formatted(
                query, repo.fullName(), result.totalCount(), result.incompleteResults() ? ", search incomplete" : ""));
        if (allowed.size() < result.matches().size()) {
            out.line("%d result(s) not shown because of this server's path policy."
                    .formatted(result.matches().size() - allowed.size()));
        }
        if (result.totalCount() > result.matches().size()) {
            out.line("Showing the first %d; narrow the query to see others.".formatted(result.matches().size()));
        }
        for (CodeSearchResult.Match match : allowed) {
            out.line("");
            out.line(match.path());
            for (String fragment : match.fragments()) {
                Lines.of(fragment).all().forEach(line -> out.line("    " + line));
                out.line("    ...");
            }
        }
        return out.finish("Narrow the query (for example with path:) to get fewer results.");
    }
}
