package io.github.raju6302.cifa.mcp.tools;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Only repositories that sent us webhooks (rows in {@code repository}) can be queried.
 * Without this check, the agent, or a prompt injected through a build log, could make
 * the server read any repo its GitHub credentials happen to reach.
 */
@Repository
class RepositoryAllowlist {

    private static final String REPO_FORMAT = "[\\w.-]+/[\\w.-]+";

    private final JdbcClient jdbc;

    RepositoryAllowlist(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @throws ToolException if the name is malformed or the repo isn't one we receive webhooks from */
    AllowedRepository require(String repo) {
        if (!repo.matches(REPO_FORMAT)) {
            throw new ToolException("'%s' is not a repository name; use owner/name, e.g. octo/shop.".formatted(repo));
        }
        // GitHub treats owner and repo names case-insensitively.
        return jdbc.sql("""
                        SELECT full_name, installation_id FROM repository
                         WHERE lower(full_name) = lower(:name)
                         ORDER BY updated_at DESC
                         LIMIT 1""")
                .param("name", repo)
                .query((rs, row) -> new AllowedRepository(rs.getString("full_name"), rs.getObject("installation_id", Long.class)))
                .optional()
                .orElseThrow(() -> new ToolException(("Repository %s is not available. Only repositories connected to "
                        + "CI Failure Assistant (ones that sent it a workflow webhook) can be read.").formatted(repo)));
    }
}
