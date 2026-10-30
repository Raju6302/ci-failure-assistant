package io.github.raju6302.cifa.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.raju6302.cifa.mcp.McpProperties;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class PathPolicyTest {

    PathPolicy policy = new PathPolicy(new McpProperties(null, List.of("evals/**", "**/*.pem"), 20_000, 4_000,
            new McpProperties.Cache(Duration.ofMinutes(1), DataSize.ofMegabytes(1))));

    @Test
    void deniesMatchingPathsHoweverTheyAreWritten() {
        assertThat(policy.isDenied("evals/sandbox-expected.json")).isTrue();
        assertThat(policy.isDenied("evals")).isTrue();
        assertThat(policy.isDenied("/evals/a/b.json")).isTrue();
        assertThat(policy.isDenied("./evals//x.json")).isTrue();
        assertThat(policy.isDenied("EVALS/X.json")).isTrue();
        assertThat(policy.isDenied("secrets/deploy/key.pem")).isTrue();
    }

    @Test
    void allowsEverythingElse() {
        assertThat(policy.isDenied("src/main/java/shop/Order.java")).isFalse();
        assertThat(policy.isDenied("docs/evals.md")).isFalse();
        assertThat(policy.isDenied("sandbox-repos/evals-like/x")).isFalse();
    }

    @Test
    void requireAllowedNormalizesOrRefuses() {
        assertThat(policy.requireAllowed("/./src//App.java")).isEqualTo("src/App.java");
        assertThatThrownBy(() -> policy.requireAllowed("evals/x.json"))
                .isInstanceOf(ToolException.class).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> policy.requireAllowed("src/../evals/x.json"))
                .isInstanceOf(ToolException.class).hasMessageContaining("..");
        assertThatThrownBy(() -> policy.requireAllowed("/"))
                .isInstanceOf(ToolException.class).hasMessageContaining("path is required");
    }
}
