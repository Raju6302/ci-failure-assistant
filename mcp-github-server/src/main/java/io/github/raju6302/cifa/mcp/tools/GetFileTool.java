package io.github.raju6302.cifa.mcp.tools;

import io.github.raju6302.cifa.github.model.FileContent;
import io.github.raju6302.cifa.mcp.McpProperties;
import io.github.raju6302.cifa.mcp.text.Lines;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

@Component
class GetFileTool implements AgentTool {

    private final RepositoryAllowlist repositories;
    private final CachedGitHub github;
    private final PathPolicy paths;
    private final int maxOutputChars;

    GetFileTool(RepositoryAllowlist repositories, CachedGitHub github, PathPolicy paths, McpProperties properties) {
        this.repositories = repositories;
        this.github = github;
        this.paths = paths;
        this.maxOutputChars = properties.maxOutputChars();
    }

    @Override
    public McpSchema.Tool definition() {
        return AgentTool.readOnly("get_file", "Read a file", """
                        Read a text file from a repository at a branch, tag or commit sha. Use it to look at the code \
                        an error points to (the file and line in a stack trace or compiler error, a failing test, a \
                        build file such as pom.xml or package.json). Pass the failed run's commit sha as ref to see \
                        exactly the code that failed. Every line is prefixed with its line number ("  42 | code"), so \
                        cite evidence as path:line. Returns the path, the line range shown and the total line count. \
                        Large files are cut at the output limit: read the rest with start_line and end_line.""",
                InputSchema.create()
                        .repo()
                        .string("path", true, "File path from the repository root, e.g. src/main/java/shop/Order.java.")
                        .string("ref", true, "Branch, tag or commit sha to read the file at. Prefer a commit sha.")
                        .integer("start_line", false, 1, null, "First line to return (1-based). Default: 1.")
                        .integer("end_line", false, 1, null, "Last line to return (inclusive). Default: end of file.")
                        .build());
    }

    @Override
    public String call(ToolArguments arguments) {
        AllowedRepository repo = repositories.require(arguments.requiredString("repo"));
        String path = paths.requireAllowed(arguments.requiredString("path"));
        String ref = arguments.requiredString("ref");
        FileContent file = github.file(repo, path, ref);
        if (file.path() != null) {
            // A symlink is answered with its target's content; the target must be allowed too.
            paths.requireAllowed(file.path());
        }
        if (!file.isText()) {
            throw new ToolException("%s is binary or larger than 1 MB (%d bytes), so it can't be shown."
                    .formatted(path, file.size()));
        }
        String header = "%s at %s in %s".formatted(path, ref, repo.fullName());
        return FileView.render(header, Lines.of(file.text()), arguments.optionalInt("start_line"),
                arguments.optionalInt("end_line"), maxOutputChars,
                next -> "Call get_file again with start_line=%d to read on.".formatted(next));
    }
}
