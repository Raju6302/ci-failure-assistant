package io.github.raju6302.cifa.mcp.tools;

/**
 * A problem the agent should be told about in plain words (unknown repo, refused path, bad
 * argument). The message becomes the tool's error result, so it must not contain secrets.
 */
class ToolException extends RuntimeException {

    ToolException(String message) {
        super(message);
    }
}
