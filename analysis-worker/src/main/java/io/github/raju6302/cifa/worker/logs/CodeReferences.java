package io.github.raju6302.cifa.worker.logs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Turns paths printed in logs into repo-relative {@link CodeReference}s. */
final class CodeReferences {

    static final int MAX_REFERENCES = 20;

    /**
     * The runner's checkout directory: {@code /home/runner/work/<repo>/<repo>/} on Linux,
     * {@code /Users/runner/work/…} on macOS, {@code D:\a\<repo>\<repo>\} on Windows and
     * {@code …/_work/<repo>/<repo>/} on self-hosted runners.
     */
    static final Pattern WORKSPACE = Pattern.compile(
            "(?:file://)?(?:/home/runner/work|/Users/runner/work|[A-Za-z]:[\\\\/]a|/[\\w.-]{1,100}(?:/[\\w.-]{1,100}){0,8}/_work)"
                    + "[\\\\/]([\\w.-]{1,100})[\\\\/]\\1[\\\\/]");
    private static final Pattern ABSOLUTE = Pattern.compile("^(?:file:/|/|[A-Za-z]:[\\\\/]|node:)");
    private static final Pattern NOT_PROJECT_CODE = Pattern.compile("(?:^|/)(?:node_modules|site-packages|dist-packages|\\.m2|\\.gradle)/");
    private static final Pattern BUILD_DIR = Pattern.compile("(?:^|/)(?:src|target|build)/");

    private CodeReferences() {
    }

    /** Removes the checkout directory wherever it appears in {@code text}. */
    static String stripWorkspace(String text) {
        return text.indexOf('/') < 0 && text.indexOf('\\') < 0 ? text : WORKSPACE.matcher(text).replaceAll("");
    }

    /**
     * The repo-relative form of {@code path}, or null when it is not the project's own code
     * (an absolute path outside the checkout, such as a toolchain, or an installed dependency).
     */
    static String repoPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String result = path.strip();
        Matcher workspace = WORKSPACE.matcher(result);
        if (workspace.lookingAt()) {
            result = result.substring(workspace.end()).replace('\\', '/');
        } else if (ABSOLUTE.matcher(result).find()) {
            return null;
        }
        while (result.startsWith("./")) {
            result = result.substring(2);
        }
        return result.isEmpty() || NOT_PROJECT_CODE.matcher(result).find() ? null : result;
    }

    static CodeReference of(String path, String line) {
        String repoPath = repoPath(path);
        if (repoPath == null) {
            return null;
        }
        return new CodeReference(repoPath, line == null ? null : parseLine(line));
    }

    static CodeReference of(String path, Integer line) {
        String repoPath = repoPath(path);
        return repoPath == null ? null : new CodeReference(repoPath, line != null && line > 0 ? line : null);
    }

    /**
     * The module directory of a multi-module build, taken from the first checkout path that
     * goes into its {@code src}, {@code target} or {@code build} directory; empty for the
     * repository root, null when the log shows no such path.
     */
    static String moduleDir(List<LogLine> lines) {
        for (LogLine line : lines) {
            Matcher workspace = WORKSPACE.matcher(line.content());
            if (workspace.find()) {
                String rest = line.content().substring(workspace.end()).replace('\\', '/');
                Matcher buildDir = BUILD_DIR.matcher(rest);
                int space = rest.indexOf(' ');
                if (buildDir.find() && (space < 0 || buildDir.start() < space)) {
                    return rest.substring(0, buildDir.start());
                }
            }
        }
        return null;
    }

    /**
     * Drops nulls and duplicates, keeps first-seen order, and resolves a path printed relative
     * to the step's working directory against a longer path for the same file seen elsewhere
     * ({@code slugify.test.js} → {@code sandbox-repos/node-test-failure/slugify.test.js}). A
     * file-only reference is dropped when the same file also appears with a line.
     */
    static List<CodeReference> merge(List<CodeReference> references) {
        List<CodeReference> present = references.stream().filter(Objects::nonNull).toList();
        List<String> paths = present.stream().map(CodeReference::path).distinct().toList();
        Map<String, CodeReference> unique = new LinkedHashMap<>();
        for (CodeReference reference : present) {
            CodeReference resolved = new CodeReference(longestMatching(reference.path(), paths), reference.line());
            unique.putIfAbsent(resolved.toString(), resolved);
        }
        List<CodeReference> result = new ArrayList<>();
        for (CodeReference reference : unique.values()) {
            boolean lineKnownElsewhere = reference.line() == null && unique.values().stream()
                    .anyMatch(other -> other.line() != null && other.path().equals(reference.path()));
            if (!lineKnownElsewhere && result.size() < MAX_REFERENCES) {
                result.add(reference);
            }
        }
        return result;
    }

    /** The last segment of a path printed with either separator. */
    static String fileName(String path) {
        return path.substring(Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\')) + 1);
    }

    private static String longestMatching(String path, List<String> paths) {
        String best = path;
        for (String candidate : paths) {
            if (candidate.length() > best.length() && candidate.endsWith("/" + path)) {
                best = candidate;
            }
        }
        return best;
    }

    private static Integer parseLine(String line) {
        try {
            int value = Integer.parseInt(line);
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
