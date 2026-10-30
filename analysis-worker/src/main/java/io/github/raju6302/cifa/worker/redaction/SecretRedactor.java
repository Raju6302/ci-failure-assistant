package io.github.raju6302.cifa.worker.redaction;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Removes credentials from CI logs before they are stored or sent to an LLM.
 *
 * <p>GitHub already masks secrets it knows about as {@code ***}; this catches the ones it
 * doesn't, such as tokens printed by a tool or credentials embedded in a URL. Patterns
 * favour catching too much over too little.
 */
@Component
public class SecretRedactor {

    public static final String MASK = "[REDACTED]";

    private record Rule(Pattern pattern, String replacement) {
    }

    private static final List<Rule> RULES = List.of(
            // PEM private key blocks (multi-line)
            rule("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----", MASK),
            // GitHub tokens: classic, OAuth, user-to-server, server-to-server, refresh, fine-grained
            rule("\\b(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{22,255})\\b", MASK),
            // AWS access key ids
            rule("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b", MASK),
            // Slack tokens
            rule("\\bxox[abprs]-[A-Za-z0-9-]{10,}\\b", MASK),
            // JSON Web Tokens
            rule("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}", MASK),
            // Authorization headers
            rule("(?i)(authorization:\\s*(?:bearer|basic|token)\\s+)[^\\s'\"]+", "$1" + MASK),
            // user:password@ in URLs, e.g. mongodb+srv://user:pass@host
            rule("(?i)\\b([a-z][a-z0-9+.-]*://)[^\\s:/@]+:[^\\s@/]+@", "$1" + MASK + "@"),
            // KEY=value / key: value where the name looks secret
            rule("(?i)\\b([a-z0-9_.-]*(?:password|passwd|secret|token|api[_-]?key|access[_-]?key)[a-z0-9_.-]*"
                    + "[\"']?\\s*[=:]\\s*)([\"']?)(?!\\*\\*\\*)[^\\s\"',;]{4,}\\2", "$1$2" + MASK + "$2"));

    public String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Rule rule : RULES) {
            result = rule.pattern().matcher(result).replaceAll(rule.replacement());
        }
        return result;
    }

    private static Rule rule(String regex, String replacement) {
        return new Rule(Pattern.compile(regex), replacement);
    }
}
