package io.github.raju6302.cifa.mcp.tools;

/**
 * A repository the tools may read.
 *
 * @param fullName       owner/name as GitHub spells it
 * @param installationId GitHub App installation to authenticate as; null means the static token
 */
record AllowedRepository(String fullName, Long installationId) {
}
