package io.github.raju6302.cifa.worker.rag;

import io.github.raju6302.cifa.worker.diagnosis.FailureContext;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Finds the code most related to a failure by combining three signals:
 * <ol>
 *   <li>files and lines the log points to (compiler errors, stack traces): strongest,</li>
 *   <li>keyword search on identifiers from the error (Postgres full-text),</li>
 *   <li>vector similarity between the error and code chunks (pgvector).</li>
 * </ol>
 * The keyword and vector rankings are merged with reciprocal rank fusion, then chunks the
 * log points to are boosted to the top. A failing test usually means a bug in the code it
 * tests, so a referenced test file also boosts its source file ({@code FooTest.java} →
 * {@code Foo.java}, {@code foo.test.js} → {@code foo.js}, {@code test_foo.py} → {@code foo.py}).
 * File names merely mentioned in the log (e.g. the script a step ran) get a small boost.
 */
public class HybridRetriever {

    private static final int CANDIDATES = 30;
    private static final int RRF_K = 60;
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,60}");
    private static final Pattern MENTIONED_FILE = Pattern.compile(
            "(?<![\\w.-])[\\w-][\\w.-]{0,80}\\.(?:sh|py|js|ts|mjs|java|kt|go|rb|rs|php|cs|gradle|xml|toml|yml|yaml|json)\\b");
    /** Test file → file under test. Group "dir" + "name" + "ext" give the source path. */
    private static final List<Pattern> TEST_TO_SOURCE = List.of(
            Pattern.compile("^(?<dir>.*/)?(?<name>[^/]+?)(?:Tests?|IT|Spec)\\.(?<ext>java|kt|scala|groovy)$"),
            Pattern.compile("^(?<dir>.*/)?(?<name>[^/]+?)\\.(?:test|spec)\\.(?<ext>js|jsx|ts|tsx|mjs|cjs)$"),
            Pattern.compile("^(?<dir>.*/)?(?<name>[^/]+?)_test\\.(?<ext>go|py)$"),
            Pattern.compile("^(?<dir>.*/)?test_(?<name>[^/]+)\\.(?<ext>py)$"));
    private static final Set<String> NOISE = Set.of(
            "error", "errors", "info", "warn", "warning", "failed", "failure", "failures", "fatal", "exception",
            "test", "tests", "run", "build", "expected", "but", "was", "the", "and", "for", "with", "from", "not",
            "line", "file", "found", "cannot", "symbol", "location", "class", "variable", "true", "false", "null",
            "maven", "mvn", "npm", "node", "java", "jar", "main", "src", "home", "runner", "work", "time", "total",
            "elapsed", "process", "completed", "exit", "code", "help", "see", "full", "stack", "trace", "debug");

    public record Snippet(String path, int startLine, int endLine, String symbol, String content, double score) {
    }

    private final JdbcClient jdbc;
    private final Embedder embedder;

    public HybridRetriever(JdbcClient jdbc, Embedder embedder) {
        this.jdbc = jdbc;
        this.embedder = embedder;
    }

    public List<Snippet> search(long repositoryId, String errorText, List<FailureContext.CodeReference> references, int limit) {
        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        List<Candidate> vector = vectorSearch(repositoryId, errorText);
        List<Candidate> keyword = keywordSearch(repositoryId, errorText);
        fuse(candidates, vector);
        fuse(candidates, keyword);
        boostReferenced(candidates, repositoryId, withSourcesUnderTest(references), 1.0);
        boostReferenced(candidates, repositoryId, mentionedFiles(errorText, references), 0.3);
        return candidates.values().stream()
                .sorted(Comparator.comparingDouble(Candidate::score).reversed())
                .limit(limit)
                .map(c -> new Snippet(c.path, c.startLine, c.endLine, c.symbol, c.content, c.score))
                .toList();
    }

    private List<Candidate> vectorSearch(long repositoryId, String errorText) {
        String vector = Embedder.toVectorLiteral(embedder.embed(errorText));
        return jdbc.sql("""
                        SELECT id, path, start_line, end_line, symbol, content FROM code_chunk
                         WHERE repository_id = :repo
                         ORDER BY embedding <=> CAST(:vector AS vector)
                         LIMIT :limit""")
                .param("repo", repositoryId).param("vector", vector).param("limit", CANDIDATES)
                .query((rs, row) -> new Candidate(rs.getLong("id"), rs.getString("path"), rs.getInt("start_line"),
                        rs.getInt("end_line"), rs.getString("symbol"), rs.getString("content")))
                .list();
    }

    private List<Candidate> keywordSearch(long repositoryId, String errorText) {
        List<String> terms = identifiers(errorText);
        if (terms.isEmpty()) {
            return List.of();
        }
        // Terms are [A-Za-z0-9_] only, so joining them with | can't inject tsquery syntax.
        return jdbc.sql("""
                        SELECT id, path, start_line, end_line, symbol, content
                          FROM code_chunk, to_tsquery('simple', :query) query
                         WHERE repository_id = :repo AND content_tsv @@ query
                         ORDER BY ts_rank_cd(content_tsv, query) DESC
                         LIMIT :limit""")
                .param("repo", repositoryId).param("query", String.join(" | ", terms)).param("limit", CANDIDATES)
                .query((rs, row) -> new Candidate(rs.getLong("id"), rs.getString("path"), rs.getInt("start_line"),
                        rs.getInt("end_line"), rs.getString("symbol"), rs.getString("content")))
                .list();
    }

    /**
     * Chunks containing a line the log points to get the full {@code weight}; other chunks of
     * the same file get part of it.
     */
    private void boostReferenced(Map<Long, Candidate> candidates, long repositoryId,
                                 List<FailureContext.CodeReference> references, double weight) {
        for (FailureContext.CodeReference reference : references) {
            String suffix = reference.path().replaceFirst("^\\./", "");
            List<Candidate> inFile = jdbc.sql("""
                            SELECT id, path, start_line, end_line, symbol, content FROM code_chunk
                             WHERE repository_id = :repo
                               AND (path = :path OR right(path, length(:path) + 1) = '/' || :path)
                             ORDER BY start_line LIMIT 50""")
                    .param("repo", repositoryId).param("path", suffix)
                    .query((rs, row) -> new Candidate(rs.getLong("id"), rs.getString("path"), rs.getInt("start_line"),
                            rs.getInt("end_line"), rs.getString("symbol"), rs.getString("content")))
                    .list();
            for (Candidate chunk : inFile) {
                boolean containsLine = reference.line() != null
                        && reference.line() >= chunk.startLine && reference.line() <= chunk.endLine;
                Candidate existing = candidates.computeIfAbsent(chunk.id, id -> chunk);
                existing.score += weight * (containsLine ? 1.0 : (reference.line() == null ? 0.5 : 0.1));
            }
        }
    }

    /** The references plus, for each test file, the source file it most likely tests. */
    static List<FailureContext.CodeReference> withSourcesUnderTest(List<FailureContext.CodeReference> references) {
        List<FailureContext.CodeReference> result = new ArrayList<>(references);
        for (FailureContext.CodeReference reference : references) {
            sourceUnderTest(reference.path()).ifPresent(source -> result.add(new FailureContext.CodeReference(source, null)));
        }
        return result;
    }

    static Optional<String> sourceUnderTest(String testPath) {
        for (Pattern rule : TEST_TO_SOURCE) {
            Matcher matcher = rule.matcher(testPath);
            if (matcher.matches()) {
                String dir = matcher.group("dir") == null ? "" : matcher.group("dir");
                // Maven/Gradle layout: the code under test lives in src/main, not src/test.
                return Optional.of((dir + matcher.group("name") + "." + matcher.group("ext"))
                        .replaceFirst("(^|/)src/test/", "$1src/main/"));
            }
        }
        return Optional.empty();
    }

    /** File names mentioned anywhere in the error text that aren't already references. */
    static List<FailureContext.CodeReference> mentionedFiles(String text, List<FailureContext.CodeReference> references) {
        Set<String> known = new HashSet<>();
        references.forEach(r -> known.add(r.path().substring(r.path().lastIndexOf('/') + 1)));
        Set<String> mentioned = new LinkedHashSet<>();
        Matcher matcher = MENTIONED_FILE.matcher(text == null ? "" : text);
        while (matcher.find() && mentioned.size() < 10) {
            if (!known.contains(matcher.group())) {
                mentioned.add(matcher.group());
            }
        }
        return mentioned.stream().map(name -> new FailureContext.CodeReference(name, null)).toList();
    }

    private static void fuse(Map<Long, Candidate> candidates, List<Candidate> ranking) {
        for (int rank = 0; rank < ranking.size(); rank++) {
            Candidate candidate = ranking.get(rank);
            candidates.computeIfAbsent(candidate.id, id -> candidate).score += 1.0 / (RRF_K + rank + 1);
        }
    }

    /** Distinctive identifiers from the error, most frequent first. */
    static List<String> identifiers(String text) {
        Map<String, Integer> counts = new HashMap<>();
        Matcher matcher = IDENTIFIER.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String token = matcher.group().toLowerCase();
            if (!NOISE.contains(token) && !token.chars().allMatch(Character::isDigit)) {
                counts.merge(token, 1, Integer::sum);
            }
        }
        Set<String> ordered = new LinkedHashSet<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(25)
                .forEach(entry -> ordered.add(entry.getKey()));
        return new ArrayList<>(ordered);
    }

    private static final class Candidate {
        final long id;
        final String path;
        final int startLine;
        final int endLine;
        final String symbol;
        final String content;
        double score;

        Candidate(long id, String path, int startLine, int endLine, String symbol, String content) {
            this.id = id;
            this.path = path;
            this.startLine = startLine;
            this.endLine = endLine;
            this.symbol = symbol;
            this.content = content;
        }

        double score() {
            return score;
        }
    }
}
