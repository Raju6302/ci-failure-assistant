package io.github.raju6302.cifa.worker.logs;

import static io.github.raju6302.cifa.worker.logs.TestLogs.WORKSPACE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GradleParserTest {

    GradleParser parser = new GradleParser();

    @Test
    void recognizesGradleOutput() {
        assertThat(parser.signature(TestLogs.step("Run ./gradlew build", "x"))).isEqualTo(2);
        assertThat(parser.signature(TestLogs.step("Run make", "* What went wrong:"))).isEqualTo(2);
        assertThat(parser.signature(TestLogs.step("Run make", "> Task :compileJava"))).isEqualTo(1);
        assertThat(parser.signature(TestLogs.step("Run make", "all good"))).isZero();
    }

    @Test
    void readsJavacErrors() {
        StepLog step = TestLogs.step("Run ./gradlew build", """
                > Task :app:compileJava FAILED
                %sapp/src/main/java/com/acme/Order.java:12: error: cannot find symbol
                        return total.plus(tax);
                                     ^
                  symbol:   method plus(BigDecimal)
                1 error
                """.formatted(WORKSPACE));

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.COMPILE_ERROR);
        assertThat(finding.summary()).isEqualTo("Order.java:12: cannot find symbol: method plus(BigDecimal)");
        assertThat(finding.codeReferences()).containsExactly(new CodeReference("app/src/main/java/com/acme/Order.java", 12));
    }

    @Test
    void readsKotlinErrors() {
        StepLog step = TestLogs.step("Run ./gradlew build",
                "e: file://%sapp/src/main/kotlin/Cart.kt:7:13 Unresolved reference: discount".formatted(WORKSPACE));

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.summary()).isEqualTo("Cart.kt:7: Unresolved reference: discount");
        assertThat(finding.codeReferences()).containsExactly(new CodeReference("app/src/main/kotlin/Cart.kt", 7));
    }

    @Test
    void readsFailedTestsWithTheShortExceptionFormat() {
        StepLog step = TestLogs.step("Run ./gradlew test", """
                > Task :billing:test

                com.acme.billing.PriceCalculatorTest > appliesPercentageDiscount() FAILED
                    org.opentest4j.AssertionFailedError at PriceCalculatorTest.java:13

                2 tests completed, 1 failed

                > Task :billing:test FAILED
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.TEST_FAILURE);
        assertThat(finding.summary())
                .isEqualTo("com.acme.billing.PriceCalculatorTest > appliesPercentageDiscount(): "
                        + "org.opentest4j.AssertionFailedError at PriceCalculatorTest.java:13");
        assertThat(finding.codeReferences()).containsExactly(
                new CodeReference("billing/src/test/java/com/acme/billing/PriceCalculatorTest.java", 13));
    }

    @Test
    void readsFailedTestsWithFullExceptions() {
        StepLog step = TestLogs.step("Run ./gradlew test", """
                CartTest > addsItem() FAILED
                    org.opentest4j.AssertionFailedError: expected: <2> but was: <3>
                        at app//com.acme.CartTest.addsItem(CartTest.java:21)
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.summary()).isEqualTo("CartTest > addsItem(): org.opentest4j.AssertionFailedError: expected <2> but was <3>");
        assertThat(finding.codeReferences()).containsExactly(new CodeReference("src/test/java/com/acme/CartTest.java", 21));
    }

    @Test
    void readsUnresolvedDependencies() {
        StepLog step = TestLogs.step("Run ./gradlew build", """
                * What went wrong:
                Execution failed for task ':app:compileJava'.
                > Could not resolve all files for configuration ':app:compileClasspath'.
                   > Could not find com.acme:payments-sdk:4.2.0.
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.DEPENDENCY);
        assertThat(finding.summary()).isEqualTo("Could not find com.acme:payments-sdk:4.2.0.");
    }

    @Test
    void fallsBackToWhatWentWrong() {
        StepLog step = TestLogs.step("Run ./gradlew build", """
                FAILURE: Build failed with an exception.

                * What went wrong:
                Execution failed for task ':app:checkstyleMain'.
                > Checkstyle rule violations were found.

                * Try:
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(finding.summary())
                .isEqualTo("Execution failed for task ':app:checkstyleMain'. > Checkstyle rule violations were found.");
    }
}
