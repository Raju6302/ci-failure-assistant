package io.github.raju6302.cifa.worker.rag;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.Set;

/**
 * Decides which files are worth indexing: source code and the build/CI files failures
 * often come from. Skips vendored, generated and binary files, and {@code evals/}, whose
 * expected answers must never reach the agent.
 */
final class IndexFilter {

    private static final Set<String> EXTENSIONS = Set.of(
            "java", "kt", "kts", "scala", "groovy", "gradle", "js", "jsx", "mjs", "cjs", "ts", "tsx", "py", "go",
            "rb", "rs", "cs", "php", "c", "h", "cc", "cpp", "hpp", "swift", "sql", "sh", "yml", "yaml", "toml",
            "xml", "json", "properties", "cfg", "ini", "tf");
    private static final Set<String> FILE_NAMES = Set.of(
            "Dockerfile", "Makefile", "Jenkinsfile", "requirements.txt", "Gemfile", "go.mod", "Cargo.toml",
            "pyproject.toml", "setup.py", "package.json", "pom.xml", "build.gradle", "settings.gradle");
    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(
            "node_modules", "vendor", "dist", "build", "target", "out", ".git", ".idea", ".gradle", ".next",
            "coverage", "__pycache__", ".venv", "venv", "evals");
    private static final Set<String> SKIPPED_FILES = Set.of(
            "package-lock.json", "yarn.lock", "pnpm-lock.yaml", "poetry.lock", "Cargo.lock", "go.sum");

    private IndexFilter() {
    }

    static boolean isIndexable(String path) {
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (SKIPPED_DIRECTORIES.contains(parts[i])) {
                return false;
            }
        }
        String name = parts[parts.length - 1];
        if (SKIPPED_FILES.contains(name) || name.endsWith(".min.js")) {
            return false;
        }
        if (FILE_NAMES.contains(name) || name.startsWith("Dockerfile")) {
            return true;
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(name.substring(dot + 1).toLowerCase());
    }

    /** Decodes UTF-8 text; empty for binary content. */
    static Optional<String> asText(byte[] content) {
        for (int i = 0; i < Math.min(content.length, 8000); i++) {
            if (content[i] == 0) {
                return Optional.empty();
            }
        }
        try {
            return Optional.of(StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString());
        } catch (CharacterCodingException e) {
            return Optional.empty();
        }
    }
}
