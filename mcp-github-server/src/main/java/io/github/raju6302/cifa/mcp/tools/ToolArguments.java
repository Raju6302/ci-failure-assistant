package io.github.raju6302.cifa.mcp.tools;

import java.util.Map;

/**
 * Typed access to a tool call's JSON arguments. The SDK already validates them against the
 * input schema; this still checks, because a missing or mistyped argument should produce
 * a message the agent can act on rather than a NullPointerException.
 */
final class ToolArguments {

    private final Map<String, Object> values;

    ToolArguments(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    String requiredString(String name) {
        String value = optionalString(name);
        if (value == null) {
            throw new ToolException("Missing required argument '%s'.".formatted(name));
        }
        return value;
    }

    /** @return the trimmed value, or null if absent or blank */
    String optionalString(String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        String text = value.toString().strip();
        return text.isEmpty() ? null : text;
    }

    long requiredLong(String name) {
        Long value = optionalLong(name);
        if (value == null) {
            throw new ToolException("Missing required argument '%s'.".formatted(name));
        }
        return value;
    }

    Integer optionalInt(String name) {
        Long value = optionalLong(name);
        if (value != null && (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)) {
            throw new ToolException("Argument '%s' is out of range.".formatted(name));
        }
        return value == null ? null : value.intValue();
    }

    Long optionalLong(String name) {
        Object value = values.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            return number.longValue();
        }
        try {
            return Long.parseLong(value.toString().strip());
        } catch (NumberFormatException e) {
            throw new ToolException("Argument '%s' must be a whole number, got: %s".formatted(name, value));
        }
    }
}
