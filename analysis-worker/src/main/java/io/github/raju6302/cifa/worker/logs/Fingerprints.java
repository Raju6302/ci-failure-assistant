package io.github.raju6302.cifa.worker.logs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fingerprints a failure so the same failure on another commit, branch or runner gets the
 * same value: SHA-256 of the category and the key error lines, with everything that changes
 * between runs masked (paths of the checkout and temp directories, timestamps, ids, numbers).
 * Words are kept, so different errors stay different.
 */
final class Fingerprints {

    private record Mask(Pattern pattern, String replacement) {
    }

    // Order matters: whole tokens (timestamps, ids) before the numbers inside them.
    private static final List<Mask> MASKS = List.of(
            mask("(?:/tmp|/var/folders|/home/runner/work/_temp|/github/runner_temp|[A-Za-z]:\\\\[\\w\\\\]{0,100}\\\\Temp)"
                    + "[^\\s'\"]{0,300}", "<tmp>"),
            mask("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(?:[.,]\\d{1,9})?(?:Z|[+-]\\d{2}:?\\d{2})?", "<time>"),
            mask("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b", "<uuid>"),
            mask("\\b(?=[0-9a-f]{0,63}\\d)[0-9a-f]{7,64}\\b", "<hex>"),
            // Random ids such as BuildKit's cache refs: long tokens that mix letters and digits.
            mask("\\b(?=[A-Za-z0-9]{0,127}\\d)(?=[A-Za-z0-9]{0,127}[A-Za-z])[A-Za-z0-9]{16,128}\\b", "<id>"),
            // Standalone numbers, also with a unit (12.5s); digits inside a name (commons-lang4) stay.
            mask("(?<![\\w.])\\d{1,20}(?:\\.\\d{1,20}){0,10}", "<n>"),
            mask("\\s+", " "));

    private Fingerprints() {
    }

    static String of(FailureCategory category, List<String> keyLines) {
        StringBuilder input = new StringBuilder(category.name());
        for (String line : keyLines) {
            input.append('\n').append(normalize(line));
        }
        return sha256(input.toString());
    }

    static String normalize(String line) {
        String result = CodeReferences.stripWorkspace(line);
        for (Mask mask : MASKS) {
            result = mask.pattern().matcher(result).replaceAll(mask.replacement());
        }
        return result.strip();
    }

    private static String sha256(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required on every Java platform", e);
        }
    }

    private static Mask mask(String regex, String replacement) {
        return new Mask(Pattern.compile(regex), replacement);
    }
}
