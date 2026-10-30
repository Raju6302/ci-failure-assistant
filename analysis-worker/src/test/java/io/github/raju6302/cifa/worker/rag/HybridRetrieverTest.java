package io.github.raju6302.cifa.worker.rag;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.raju6302.cifa.worker.diagnosis.FailureContext;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class HybridRetrieverTest {

    @ParameterizedTest
    @CsvSource({
            "sandbox/java/src/test/java/shop/PriceCalculatorTest.java, sandbox/java/src/main/java/shop/PriceCalculator.java",
            "src/test/kotlin/OrderServiceTests.kt, src/main/kotlin/OrderService.kt",
            "web/slugify.test.js, web/slugify.js",
            "app/components/Button.spec.tsx, app/components/Button.tsx",
            "py/test_durations.py, py/durations.py",
            "test_durations.py, durations.py",
            "pkg/server/handler_test.go, pkg/server/handler.go"})
    void mapsTestFilesToTheCodeTheyTest(String test, String source) {
        assertThat(HybridRetriever.sourceUnderTest(test)).contains(source);
    }

    @Test
    void leavesOrdinaryFilesAlone() {
        assertThat(HybridRetriever.sourceUnderTest("src/main/java/shop/Order.java")).isEmpty();
        assertThat(HybridRetriever.sourceUnderTest("latest.js")).isEmpty();
    }

    @Test
    void findsFileNamesMentionedInTheLog() {
        String log = "Run ./slow-integration-test.sh\nError: Cannot find module '/home/runner/work/app/app/lib/util.js'";

        assertThat(HybridRetriever.mentionedFiles(log, List.of(new FailureContext.CodeReference("lib/util.js", 3))))
                .extracting(FailureContext.CodeReference::path)
                .containsExactly("slow-integration-test.sh");
    }

    @Test
    void picksDistinctiveIdentifiers() {
        assertThat(HybridRetriever.identifiers("[ERROR] cannot find symbol: method price() in InvoiceService"))
                .contains("price", "invoiceservice")
                .doesNotContain("error", "cannot", "symbol");
    }
}
