package io.github.raju6302.cifa.worker.rag;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a source file into chunks of about {@code targetLines}, preferring to start a
 * chunk where a class, function or method is declared so a chunk is one coherent unit.
 * Each chunk records its line range (for citations) and the declared symbol, if any.
 */
class CodeChunker {

    record Chunk(int startLine, int endLine, String symbol, String content) {
    }

    // Bounded, anchored patterns: input is untrusted repository content.
    private static final Pattern DECLARATION = Pattern.compile(
            "^\\s{0,12}(?:(?:public|protected|private|internal|static|final|abstract|sealed|open|override|"
                    + "export|default|async|suspend|data)\\s{1,4}){0,5}"
                    + "(?:class|interface|enum|record|object|trait|struct|impl|fun|def|function|func)\\s{1,4}"
                    + "(?:\\([^)]{0,80}\\)\\s{0,4})?([A-Za-z_$][\\w$]{0,80})");
    private static final Pattern JAVA_METHOD = Pattern.compile(
            "^\\s{0,12}(?:(?:public|protected|private|static|final|synchronized|abstract)\\s{1,4}){1,5}"
                    + "[\\w<>\\[\\],.? ]{1,80}?\\s([a-zA-Z_$][\\w$]{0,80})\\s{0,4}\\(");
    private static final Pattern JS_ARROW = Pattern.compile(
            "^\\s{0,12}(?:export\\s{1,4})?(?:const|let)\\s{1,4}([A-Za-z_$][\\w$]{0,80})\\s{0,4}=\\s{0,4}(?:async\\s{0,4})?\\(");

    private final int targetLines;
    private final int minLines;
    private final int maxLines;

    CodeChunker(int targetLines) {
        this.targetLines = targetLines;
        this.minLines = Math.max(5, targetLines / 4);
        this.maxLines = targetLines * 2;
    }

    List<Chunk> chunk(String path, String content) {
        String[] lines = content.split("\n", -1);
        int total = lines.length > 0 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        List<Chunk> chunks = new ArrayList<>();
        if (total == 0) {
            return chunks;
        }
        if (total <= targetLines) {
            chunks.add(new Chunk(1, total, firstSymbol(lines, total, fileName(path)), join(lines, 0, total)));
            return chunks;
        }
        int start = 0;
        String symbol = symbolAt(lines[0]);
        for (int i = 1; i < total; i++) {
            int size = i - start;
            String declared = symbolAt(lines[i]);
            boolean atDeclaration = declared != null && size >= minLines;
            if (atDeclaration || size >= maxLines || (size >= targetLines && lines[i].isBlank())) {
                chunks.add(new Chunk(start + 1, i, symbol, join(lines, start, i)));
                start = i;
                symbol = declared;
            } else if (symbol == null && declared != null) {
                symbol = declared;
            }
        }
        chunks.add(new Chunk(start + 1, total, symbol, join(lines, start, total)));
        return chunks;
    }

    static String symbolAt(String line) {
        if (line.length() > 300) {
            return null;
        }
        for (Pattern pattern : List.of(DECLARATION, JAVA_METHOD, JS_ARROW)) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private static String firstSymbol(String[] lines, int total, String fallback) {
        for (int i = 0; i < total; i++) {
            String symbol = symbolAt(lines[i]);
            if (symbol != null) {
                return symbol;
            }
        }
        return fallback;
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String join(String[] lines, int from, int to) {
        return String.join("\n", java.util.Arrays.asList(lines).subList(from, to));
    }
}
