package io.github.raju6302.cifa.worker.logs;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CodeReferencesTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/home/runner/work/shop/shop/src/App.java",
            "file:///home/runner/work/shop/shop/src/App.java",
            "/Users/runner/work/shop/shop/src/App.java",
            "D:\\a\\shop\\shop\\src\\App.java",
            "/opt/actions-runner/_work/shop/shop/src/App.java",
            "src/App.java",
            "./src/App.java"})
    void makesPathsRepoRelative(String path) {
        assertThat(CodeReferences.repoPath(path)).isEqualTo("src/App.java");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/opt/hostedtoolcache/Python/3.13.17/x64/lib/python3.13/json/__init__.py",
            "/home/runner/work/_temp/script.sh",
            "node:internal/test_runner/test",
            "node_modules/jest-circus/build/run.js",
            "/home/runner/work/shop/shop/.venv/lib/python3.13/site-packages/requests/api.py",
            " "})
    void ignoresCodeOutsideTheProject(String path) {
        assertThat(CodeReferences.repoPath(path)).isNull();
    }

    @Test
    void mergeResolvesRelativePathsAndDropsDuplicates() {
        List<CodeReference> merged = CodeReferences.merge(Arrays.asList(
                new CodeReference("web/src/slugify.test.js", 10),
                new CodeReference("slugify.test.js", 9),
                null,
                new CodeReference("web/src/slugify.test.js", 10),
                new CodeReference("web/src/slugify.test.js", null),
                new CodeReference("pom.xml", null)));

        assertThat(merged).containsExactly(
                new CodeReference("web/src/slugify.test.js", 10),
                new CodeReference("web/src/slugify.test.js", 9),
                new CodeReference("pom.xml", null));
    }

    @Test
    void findsTheModuleOfAMultiModuleBuild() {
        List<LogLine> lines = LogNormalizer.normalize("""
                [INFO] skip /opt/tool/src/main/resources
                [ERROR] See /home/runner/work/shop/shop/services/billing/target/surefire-reports for the results
                """);

        assertThat(CodeReferences.moduleDir(lines)).isEqualTo("services/billing");
        assertThat(CodeReferences.moduleDir(LogNormalizer.normalize("/home/runner/work/shop/shop/src/main/App.java")))
                .isEmpty();
        assertThat(CodeReferences.moduleDir(LogNormalizer.normalize("no paths here"))).isNull();
    }
}
