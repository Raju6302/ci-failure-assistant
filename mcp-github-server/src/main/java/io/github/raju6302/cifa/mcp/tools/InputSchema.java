package io.github.raju6302.cifa.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a tool's JSON-schema input definition. The descriptions are what the model reads
 * to decide which values to pass, so they say what each argument means and its limits.
 */
final class InputSchema {

    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    static InputSchema create() {
        return new InputSchema();
    }

    /** The owner/name argument every GitHub tool takes. */
    InputSchema repo() {
        return string("repo", true, "Repository as owner/name, e.g. octo/shop. Only repositories connected to "
                + "CI Failure Assistant can be read: use the repository of the failed run.");
    }

    InputSchema string(String name, boolean isRequired, String description) {
        return property(name, isRequired, Map.of("type", "string", "description", description));
    }

    InputSchema integer(String name, boolean isRequired, long minimum, Long maximum, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        schema.put("minimum", minimum);
        if (maximum != null) {
            schema.put("maximum", maximum);
        }
        schema.put("description", description);
        return property(name, isRequired, schema);
    }

    /** Unknown arguments are rejected, so a misspelt one (startLine) fails loudly instead of being ignored. */
    Map<String, Object> build() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private InputSchema property(String name, boolean isRequired, Map<String, Object> schema) {
        properties.put(name, schema);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }
}
