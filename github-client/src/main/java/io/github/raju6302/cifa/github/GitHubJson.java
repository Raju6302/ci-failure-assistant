package io.github.raju6302.cifa.github;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** JSON mapper for GitHub payloads: snake_case fields, unknown fields ignored. */
public final class GitHubJson {

    private static final JsonMapper MAPPER = JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private GitHubJson() {
    }

    public static JsonMapper mapper() {
        return MAPPER;
    }
}
