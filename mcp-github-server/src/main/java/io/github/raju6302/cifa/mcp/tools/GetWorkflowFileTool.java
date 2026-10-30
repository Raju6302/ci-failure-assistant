package io.github.raju6302.cifa.mcp.tools;

import io.github.raju6302.cifa.github.model.FileContent;
import io.github.raju6302.cifa.github.model.WorkflowRun;
import io.github.raju6302.cifa.mcp.McpProperties;
import io.github.raju6302.cifa.mcp.text.Lines;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.stereotype.Component;

@Component
class GetWorkflowFileTool implements AgentTool {

    private final RepositoryAllowlist repositories;
    private final CachedGitHub github;
    private final PathPolicy paths;
    private final int maxOutputChars;

    GetWorkflowFileTool(RepositoryAllowlist repositories, CachedGitHub github, PathPolicy paths,
            McpProperties properties) {
        this.repositories = repositories;
        this.github = github;
        this.paths = paths;
        this.maxOutputChars = properties.maxOutputChars();
    }

    @Override
    public McpSchema.Tool definition() {
        return AgentTool.readOnly("get_workflow_file", "Read a run's workflow file", """
                        Read the GitHub Actions workflow YAML that a run executed, exactly as it was at the run's \
                        commit. Use it to see the steps, commands, versions (Java, Node, Python), services, caches, \
                        timeouts and environment of the failing job, e.g. for configuration errors, timeouts or \
                        missing tools. Returns the workflow path, the commit sha and the file with numbered lines.""",
                InputSchema.create()
                        .repo()
                        .integer("run_id", true, 1, null,
                                "GitHub's id of the workflow run (the number in .../actions/runs/<run_id>).")
                        .build());
    }

    @Override
    public String call(ToolArguments arguments) {
        AllowedRepository repo = repositories.require(arguments.requiredString("repo"));
        long runId = arguments.requiredLong("run_id");
        WorkflowRun run = github.workflowRun(repo, runId);
        if (run.path() == null || run.path().isBlank() || run.headSha() == null) {
            throw new ToolException(("Run %d has no workflow file in the repository; it was started by GitHub "
                    + "itself (e.g. Dependabot or Pages).").formatted(runId));
        }
        // Runs of reusable workflows report "path@ref"; the file is the part before the "@".
        String path = paths.requireAllowed(run.path().split("@", 2)[0]);
        FileContent file = github.file(repo, path, run.headSha());
        if (!file.isText()) {
            throw new ToolException("The workflow file %s can't be shown (binary or larger than 1 MB).".formatted(path));
        }
        String header = "Workflow \"%s\" of run %d (attempt %d, branch %s): %s at %s in %s".formatted(
                run.name(), runId, run.runAttempt(), run.headBranch(), path, run.headSha(), repo.fullName());
        return FileView.render(header, Lines.of(file.text()), null, null, maxOutputChars,
                next -> "Read the rest with get_file (path=%s, ref=%s, start_line=%d).".formatted(path, run.headSha(), next));
    }
}
