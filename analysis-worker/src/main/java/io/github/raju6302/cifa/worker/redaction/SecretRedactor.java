package io.github.raju6302.cifa.worker.redaction;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Removes credentials from CI logs before they are stored or sent to an LLM.
 *
 * <p>GitHub already masks secrets it knows about as {@code ***}; this catches the ones it
 * doesn't, such as tokens printed by a tool or credentials embedded in a URL. Patterns
 * favour catching too much over too little. Logs are untrusted input, so every quantifier
 * is bounded or possessive to keep matching linear.
 */
@Component
public class SecretRedactor {

    public static final String MASK = "[REDACTED]";

    /**
     * The redacted text and how many secrets were removed. The count is for logs and
     * metrics; what was removed is never kept.
     */
    public record Redaction(String text, int secretsRemoved) {
    }

    private record Rule(Pattern pattern, String replacement) {
    }

    // Rules run one after another; a token an earlier rule already masked must not count twice.
    private static final String NOT_MASKED = "(?!" + Pattern.quote(MASK) + ")";

    private static final List<Rule> RULES = List.of(
            // PEM private key blocks (multi-line). The body is base64 plus a few single dashes in
            // legacy headers (Proc-Type, DEK-Info: AES-128-CBC); matching it possessively up to the
            // next "--" keeps a log full of BEGIN lines without END from being rescanned.
            rule("-----BEGIN [A-Z ]{0,40}PRIVATE KEY-----[^-]{0,16384}+(?:-[^-]{1,16384}+){0,8}"
                    + "-----END [A-Z ]{0,40}PRIVATE KEY-----", MASK),
            // GitHub tokens: classic, OAuth, user-to-server, server-to-server, refresh, fine-grained
            rule("\\b(?:gh[pousr]_[A-Za-z0-9]{36,255}|github_pat_[A-Za-z0-9_]{22,255})\\b", MASK),
            // GitLab personal, project and group access tokens
            rule("\\bglpat-[A-Za-z0-9_-]{20,255}", MASK),
            // npm access tokens
            rule("\\bnpm_[A-Za-z0-9]{36}\\b", MASK),
            // PyPI API tokens (a macaroon, always long)
            rule("\\bpypi-[A-Za-z0-9_-]{50,1024}", MASK),
            // Docker Hub personal access tokens
            rule("\\bdckr_pat_[A-Za-z0-9_-]{20,255}", MASK),
            // AWS access key ids
            rule("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b", MASK),
            // Google API keys
            rule("\\bAIza[0-9A-Za-z_-]{35}(?![0-9A-Za-z_-])", MASK),
            // Stripe secret and restricted keys
            rule("\\b(?:sk|rk)_(?:live|test)_[0-9A-Za-z]{16,255}\\b", MASK),
            // Anthropic and OpenAI API keys
            rule("\\bsk-(?:ant-|proj-)[A-Za-z0-9_-]{20,255}", MASK),
            // SendGrid API keys
            rule("\\bSG\\.[A-Za-z0-9_-]{16,64}\\.[A-Za-z0-9_-]{16,64}", MASK),
            // Slack tokens and incoming webhook URLs
            rule("\\bxox[abprs]-[A-Za-z0-9-]{10,255}", MASK),
            rule("https://hooks\\.slack\\.com/services/[A-Za-z0-9/]{20,255}", MASK),
            // JSON Web Tokens
            rule("\\beyJ[A-Za-z0-9_-]{8,4096}\\.[A-Za-z0-9_-]{8,4096}\\.[A-Za-z0-9_-]{8,4096}", MASK),
            // Authorization headers
            rule("(?i)(authorization:\\s{0,10}(?:bearer|basic|token)\\s{1,10})" + NOT_MASKED + "[^\\s'\"]++", "$1" + MASK),
            // user:password@ in URLs, e.g. mongodb+srv://user:pass@host
            rule("(?i)\\b([a-z][a-z0-9+.-]{0,30}://)" + NOT_MASKED + "[^\\s:/@]{1,256}:[^\\s@/]{1,256}@", "$1" + MASK + "@"),
            // KEY=value / key: value where the name looks secret. Matching starts at the keyword
            // (any prefix such as DB_ stays as it is), so no work is wasted on every word of a line.
            rule("(?i)((?:password|passwd|secret|token|api[_-]?key|access[_-]?key)[a-z0-9_.-]{0,64}"
                    + "[\"']?\\s{0,10}[=:]\\s{0,10})([\"']?)(?!\\*\\*\\*)" + NOT_MASKED + "[^\\s\"',;]{4,}+\\2",
                    "$1$2" + MASK + "$2"));

    public Redaction redact(String text) {
        if (text == null || text.isEmpty()) {
            return new Redaction(text, 0);
        }
        String result = text;
        int removed = 0;
        for (Rule rule : RULES) {
            Matcher matcher = rule.pattern().matcher(result);
            StringBuilder redacted = null;
            while (matcher.find()) {
                if (redacted == null) {
                    redacted = new StringBuilder(result.length());
                }
                matcher.appendReplacement(redacted, rule.replacement());
                removed++;
            }
            if (redacted != null) {
                matcher.appendTail(redacted);
                result = redacted.toString();
            }
        }
        return new Redaction(result, removed);
    }

    private static Rule rule(String regex, String replacement) {
        return new Rule(Pattern.compile(regex), replacement);
    }
}
