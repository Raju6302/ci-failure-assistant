package io.github.raju6302.cifa.worker.logs;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NodeParserTest {

    NodeParser parser = new NodeParser();

    @Test
    void recognizesNodeOutput() {
        assertThat(parser.signature(TestLogs.step("Run npm test", "x"))).isEqualTo(2);
        assertThat(parser.signature(TestLogs.step("Run make", "Test Suites: 1 failed, 1 total"))).isEqualTo(2);
        assertThat(parser.signature(TestLogs.step("Run make", "not ok 1 - adds"))).isEqualTo(1);
        assertThat(parser.signature(TestLogs.step("Run make", "[INFO] BUILD FAILURE"))).isZero();
    }

    @Test
    void readsJestFailures() {
        StepLog step = TestLogs.step("Run npx jest", """
                FAIL src/sum.test.js
                  ● math › adds numbers

                    expect(received).toBe(expected) // Object.is equality

                    Expected: 3
                    Received: 4

                      3 | test('adds numbers', () => {
                    > 4 |   expect(sum(1, 2)).toBe(3);
                        |                     ^

                      at Object.toBe (src/sum.test.js:4:21)

                Test Suites: 1 failed, 1 total
                Tests:       1 failed, 1 total
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.TEST_FAILURE);
        assertThat(finding.summary()).isEqualTo("math › adds numbers: expected 3 but received 4");
        assertThat(finding.codeReferences()).containsExactly(new CodeReference("src/sum.test.js", 4));
        assertThat(finding.firstLine()).isEqualTo(2);
        assertThat(finding.lastLine()).isEqualTo(13);
    }

    @Test
    void aJestSuiteThatCannotLoadAPackageIsADependencyProblem() {
        StepLog step = TestLogs.step("Run npx jest", """
                FAIL src/api.test.ts
                  ● Test suite failed to run

                    Cannot find module 'axios' from 'src/api.ts'
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.DEPENDENCY);
        assertThat(finding.summary()).isEqualTo("Test suite failed to run: Cannot find module 'axios' from 'src/api.ts'");
    }

    @Test
    void readsTapFailures() {
        StepLog step = TestLogs.step("Run node --test --test-reporter=tap", """
                TAP version 13
                # Subtest: collapses punctuation
                not ok 2 - collapses punctuation
                  ---
                  duration_ms: 1.0
                  failureType: 'testCodeFailure'
                  error: |-
                    Expected values to be strictly equal:
                  code: 'ERR_ASSERTION'
                  ...
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.TEST_FAILURE);
        assertThat(finding.summary()).isEqualTo("collapses punctuation: Expected values to be strictly equal:");
    }

    @Test
    void readsRegistryErrors() {
        StepLog step = TestLogs.step("Run npm ci", """
                npm error code E404
                npm error 404 Not Found - GET https://registry.npmjs.org/left-padx - Not found
                npm error 404  'left-padx@^1.0.0' is not in this registry.
                npm error A complete log of this run can be found in: /home/runner/.npm/_logs/debug.log
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.DEPENDENCY);
        assertThat(finding.summary()).isEqualTo("npm E404: 404 Not Found - GET https://registry.npmjs.org/left-padx - Not found");
    }

    @Test
    void aMissingScriptIsAConfigError() {
        Finding finding = parser.parse(TestLogs.step("Run npm run lint", "npm error Missing script: \"lint\"")).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.CONFIG_ERROR);
        assertThat(finding.summary()).isEqualTo("npm: Missing script: \"lint\"");
    }

    @Test
    void leavesAFailedLifecycleScriptToTheScriptsOwnOutput() {
        StepLog step = TestLogs.step("Run npm test", """
                npm ERR! code ELIFECYCLE
                npm ERR! errno 1
                npm ERR! shop@1.0.0 test: `node --test`
                npm ERR! Exit status 1
                """);

        assertThat(parser.parse(step)).isEmpty();
    }

    @Test
    void readsTypeScriptErrors() {
        StepLog step = TestLogs.step("Run npx tsc --noEmit", """
                src/cart.ts(14,7): error TS2322: Type 'string' is not assignable to type 'number'.
                src/cart.ts(20,3): error TS2304: Cannot find name 'discount'.
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.COMPILE_ERROR);
        assertThat(finding.summary())
                .isEqualTo("cart.ts(14): TS2322 Type 'string' is not assignable to type 'number'. (and 1 more error)");
        assertThat(finding.codeReferences())
                .containsExactly(new CodeReference("src/cart.ts", 14), new CodeReference("src/cart.ts", 20));
    }
}
