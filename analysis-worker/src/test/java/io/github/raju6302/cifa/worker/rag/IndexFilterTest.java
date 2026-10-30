package io.github.raju6302.cifa.worker.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

class IndexFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"src/main/java/shop/Order.java", "web/app.tsx", "pom.xml", "Dockerfile", "docker/Dockerfile.prod",
            ".github/workflows/ci.yml", "requirements.txt", "scripts/deploy.sh"})
    void indexesCodeAndBuildFiles(String path) {
        assertThat(IndexFilter.isIndexable(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"evals/sandbox-expected.json", "web/node_modules/react/index.js", "target/classes/A.java",
            ".git/config.json", ".claude/worktrees/x/A.java", ".idea/misc.xml", "package-lock.json", "app.min.js",
            "README.md", "logo.png"})
    void skipsEverythingElse(String path) {
        assertThat(IndexFilter.isIndexable(path)).isFalse();
    }

    @Test
    void readsOnlyUtf8Text() {
        assertThat(IndexFilter.asText("héllo".getBytes(StandardCharsets.UTF_8))).contains("héllo");
        assertThat(IndexFilter.asText(new byte[] {'P', 'K', 3, 4, 0, 0})).isEmpty();
        assertThat(IndexFilter.asText(new byte[] {(byte) 0xC3, (byte) 0x28})).isEmpty();
    }
}
