package io.github.raju6302.cifa.worker.diagnosis;

import java.util.List;
import java.util.Map;

/** The tools the diagnosis agent can call. Served by mcp-github-server in production. */
public interface AgentTools {

    List<Spec> list();

    Result call(String name, Map<String, Object> arguments);

    /** @param inputSchema JSON Schema of the arguments */
    record Spec(String name, String description, Map<String, Object> inputSchema) {
    }

    record Result(String text, boolean isError) {
    }
}
