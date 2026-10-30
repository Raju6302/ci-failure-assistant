package io.github.raju6302.cifa.worker.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class CodeChunkerTest {

    CodeChunker chunker = new CodeChunker(20);

    @Test
    void keepsSmallFilesWholeAndNamesTheirFirstDeclaration() {
        List<CodeChunker.Chunk> chunks = chunker.chunk("src/shop/Price.java", """
                package shop;

                public class PriceCalculator {
                    public double applyDiscount(double price, double percent) {
                        return price * (1 - percent);
                    }
                }
                """);

        assertThat(chunks).singleElement().satisfies(chunk -> {
            assertThat(chunk.startLine()).isEqualTo(1);
            assertThat(chunk.endLine()).isEqualTo(7);
            assertThat(chunk.symbol()).isEqualTo("PriceCalculator");
        });
    }

    @Test
    void splitsLargeFilesAtDeclarationsAndCoversEveryLine() {
        String file = IntStream.range(0, 6)
                .mapToObj(i -> "def handler_%d(event):\n%s".formatted(i, "    x = 1\n".repeat(12)))
                .collect(Collectors.joining("\n"));

        List<CodeChunker.Chunk> chunks = chunker.chunk("app/handlers.py", file);

        assertThat(chunks).hasSizeGreaterThan(3);
        assertThat(chunks).extracting(CodeChunker.Chunk::symbol).contains("handler_0", "handler_3", "handler_5");
        assertThat(chunks.getFirst().startLine()).isEqualTo(1);
        for (int i = 1; i < chunks.size(); i++) {
            assertThat(chunks.get(i).startLine()).isEqualTo(chunks.get(i - 1).endLine() + 1);
        }
        assertThat(chunks.getLast().endLine()).isEqualTo((int) file.lines().count());
    }

    @Test
    void capsChunksWithoutDeclarations() {
        String file = "line\n".repeat(200);

        assertThat(chunker.chunk("data.txt.sql", file)).allSatisfy(chunk ->
                assertThat(chunk.endLine() - chunk.startLine() + 1).isLessThanOrEqualTo(40));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "public class InvoiceService {|InvoiceService",
            "    public BigDecimal invoiceTotal(Order order) {|invoiceTotal",
            "export function slugify(title) {|slugify",
            "export const parse = async (text) => {|parse",
            "def parse_duration(text: str) -> int:|parse_duration",
            "func (s *Server) Handle(w http.ResponseWriter) {|Handle",
            "    record LineItem(String sku, int quantity) {|LineItem"})
    void recognizesDeclarations(String line, String symbol) {
        assertThat(CodeChunker.symbolAt(line)).isEqualTo(symbol);
    }

    @Test
    void ignoresOrdinaryStatements() {
        assertThat(CodeChunker.symbolAt("        return price * (1 - percent);")).isNull();
        assertThat(CodeChunker.symbolAt("x".repeat(5000))).isNull();
    }
}
