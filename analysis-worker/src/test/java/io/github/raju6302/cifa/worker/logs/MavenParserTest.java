package io.github.raju6302.cifa.worker.logs;

import static io.github.raju6302.cifa.worker.logs.TestLogs.WORKSPACE;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MavenParserTest {

    MavenParser parser = new MavenParser();

    @Test
    void recognizesMavenOutput() {
        assertThat(parser.signature(TestLogs.step("Run ./mvnw verify", "anything"))).isEqualTo(2);
        assertThat(parser.signature(TestLogs.step("Run make", "[INFO] BUILD FAILURE"))).isEqualTo(2);
        assertThat(parser.signature(TestLogs.step("Run make", "[INFO] compiling"))).isEqualTo(1);
        assertThat(parser.signature(TestLogs.step("Run npm test", "✖ test failed"))).isZero();
    }

    @Test
    void readsCompilerErrorsOnceEachWithRepoRelativePaths() {
        String error = "[ERROR] " + WORKSPACE + "billing/src/main/java/com/acme/Invoice.java:[42,17] cannot find symbol";
        StepLog step = TestLogs.step("Run mvn verify", """
                [ERROR] COMPILATION ERROR :
                %1$s
                  symbol:   variable taxRate
                [INFO] 1 error
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.15.0:compile on project billing
                %1$s
                """.formatted(error));

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.COMPILE_ERROR);
        assertThat(finding.summary()).isEqualTo("Invoice.java:42: cannot find symbol: variable taxRate");
        assertThat(finding.codeReferences()).containsExactly(new CodeReference("billing/src/main/java/com/acme/Invoice.java", 42));
        assertThat(finding.firstLine()).isEqualTo(2);
        assertThat(finding.lastLine()).isEqualTo(4);
    }

    @Test
    void readsSurefire2TestErrorsAndMapsStackFramesToSources() {
        StepLog step = TestLogs.step("Run mvn verify", """
                [INFO] Running com.acme.cart.CartTest
                [ERROR] Tests run: 3, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 0.1 s <<< FAILURE! - in com.acme.cart.CartTest
                [ERROR] addsItem(com.acme.cart.CartTest)  Time elapsed: 0.01 s  <<< ERROR!
                java.lang.NullPointerException: Cannot invoke "com.acme.cart.Item.price()" because "item" is null
                \tat com.acme.cart.Cart.add(Cart.java:21)
                \tat com.acme.cart.CartTest.addsItem(CartTest.java:15)
                \tat java.base/java.lang.reflect.Method.invoke(Method.java:580)
                [INFO] Results:
                [ERROR] Errors:\s
                [ERROR]   CartTest.addsItem:15 » NullPointer Cannot invoke "com.acme.cart.Item.price()"
                [ERROR]   CartTest.removesItem:30 » IllegalState cart is empty
                [INFO]
                [ERROR] Tests run: 3, Failures: 0, Errors: 2, Skipped: 0
                [ERROR] See %scart/target/surefire-reports for the individual test results.
                """.formatted(WORKSPACE));

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.TEST_FAILURE);
        assertThat(finding.summary())
                .isEqualTo("CartTest.addsItem: NullPointer Cannot invoke \"com.acme.cart.Item.price()\" (and 1 more failure)");
        assertThat(finding.codeReferences()).containsExactly(
                new CodeReference("cart/src/main/java/com/acme/cart/Cart.java", 21),
                new CodeReference("cart/src/test/java/com/acme/cart/CartTest.java", 15));
        assertThat(finding.firstLine()).isEqualTo(2);
        assertThat(finding.lastLine()).isEqualTo(13);
    }

    @Test
    void readsDependencyResolutionFailures() {
        StepLog step = TestLogs.step("Run mvn verify", """
                [INFO] Building app 1.0.0
                [INFO]   from app/pom.xml
                [ERROR] Failed to execute goal on project app: Could not resolve dependencies for project com.acme:app:jar:1.0.0
                [ERROR] dependency: com.acme:missing-lib:jar:2.0 (compile)
                [ERROR] \tcom.acme:missing-lib:jar:2.0 was not found in https://repo.maven.apache.org/maven2 during a previous attempt.
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.DEPENDENCY);
        assertThat(finding.summary()).startsWith("com.acme:missing-lib:jar:2.0 was not found in https://repo.maven.apache.org");
        assertThat(finding.codeReferences()).containsExactly(new CodeReference("app/pom.xml", null));
    }

    @Test
    void namesAnyOtherFailedGoal() {
        StepLog step = TestLogs.step("Run mvn verify", """
                [ERROR] Failed to execute goal org.apache.maven.plugins:maven-enforcer-plugin:3.5.0:enforce (enforce) on project app: \
                Some Enforcer rules have failed.
                """);

        Finding finding = parser.parse(step).orElseThrow();

        assertThat(finding.category()).isEqualTo(FailureCategory.UNKNOWN);
        assertThat(finding.summary())
                .isEqualTo("org.apache.maven.plugins:maven-enforcer-plugin:3.5.0:enforce: Some Enforcer rules have failed.");
    }

    @Test
    void findsNothingInOtherOutput() {
        assertThat(parser.parse(TestLogs.step("Run mvn verify", "[INFO] BUILD SUCCESS"))).isEmpty();
    }
}
