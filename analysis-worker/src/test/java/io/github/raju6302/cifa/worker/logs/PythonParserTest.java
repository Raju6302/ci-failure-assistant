package io.github.raju6302.cifa.worker.logs;

import static io.github.raju6302.cifa.worker.logs.TestLogs.WORKSPACE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PythonParserTest {

    PythonParser parser = new PythonParser();

    @Test
    void recognizesPythonOutput() {
        assertThat(parser.signature(TestLogs.step("Run pytest -q", "x"))).isEqualTo(2);
        assertThat(parser.signature(TestLogs.step("Run make", "FAILED tests/test_a.py::test_b - assert 1 == 2"))).isEqualTo(2);
        assertThat(parser.signature(TestLogs.step("Run make", "Traceback (most recent call last):"))).isEqualTo(1);
        assertThat(parser.signature(TestLogs.step("Run make", "[INFO] BUILD FAILURE"))).isZero();
    }

    @Test
    void readsUnresolvableRequirements() {
        StepLog step = TestLogs.step("Run pip install -r requirements.txt", """
                ERROR: Could not find a version that satisfies the requirement requestz==2.0 (from versions: none)
                ERROR: No matching distribution found for requestz==2.0
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.DEPENDENCY);
        assertThat(finding.summary()).contains("requestz==2.0");
    }

    @Test
    void aMissingRequirementsFileIsAConfigError() {
        StepLog step = TestLogs.step("Run pip install -r requirements-dev.txt",
                "ERROR: Could not open requirements file: [Errno 2] No such file or directory: 'requirements-dev.txt'");

        assertThat(parser.parse(step).orElseThrow().category()).isEqualTo(FailureCategory.CONFIG_ERROR);
    }

    @Test
    void anImportErrorDuringCollectionIsADependencyProblem() {
        StepLog step = TestLogs.step("Run pytest", """
                ==================================== ERRORS ====================================
                _____________________ ERROR collecting tests/test_api.py ______________________
                ImportError while importing test module '%stests/test_api.py'.
                E   ModuleNotFoundError: No module named 'requests'
                =========================== short test summary info ============================
                ERROR tests/test_api.py
                !!!!!!!!!!!!!!!!!!!! Interrupted: 1 error during collection !!!!!!!!!!!!!!!!!!!!
                """.formatted(WORKSPACE));

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.DEPENDENCY);
        assertThat(finding.summary()).isEqualTo("tests/test_api.py: ModuleNotFoundError: No module named 'requests'");
        assertThat(finding.codeReferences()).containsExactly(new CodeReference("tests/test_api.py", null));
    }

    @Test
    void readsFailuresWithoutTheShortSummary() {
        StepLog step = TestLogs.step("Run pytest -rN", """
                =================================== FAILURES ===================================
                ________________________________ test_refund ___________________________________

                    def test_refund():
                >       assert refund(10) == -10
                E       assert 10 == -10

                tests/test_payments.py:7: AssertionError
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.TEST_FAILURE);
        assertThat(finding.summary()).isEqualTo("test_refund: assert 10 == -10");
        assertThat(finding.codeReferences()).containsExactly(new CodeReference("tests/test_payments.py", 7));
    }

    @Test
    void readsUncaughtTracebacks() {
        StepLog step = TestLogs.step("Run python scripts/migrate.py", """
                Traceback (most recent call last):
                  File "%1$sscripts/migrate.py", line 12, in <module>
                    run()
                  File "%1$sscripts/migrate.py", line 8, in run
                    raise ValueError("DATABASE_URL must point to Postgres")
                  File "/opt/hostedtoolcache/Python/3.13.17/x64/lib/python3.13/json/__init__.py", line 3, in loads
                ValueError: DATABASE_URL must point to Postgres
                """.formatted(WORKSPACE));

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(finding.summary()).isEqualTo("ValueError: DATABASE_URL must point to Postgres");
        assertThat(finding.codeReferences())
                .containsExactly(new CodeReference("scripts/migrate.py", 12), new CodeReference("scripts/migrate.py", 8));
    }
}
