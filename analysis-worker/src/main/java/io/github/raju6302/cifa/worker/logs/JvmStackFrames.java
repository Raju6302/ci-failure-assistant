package io.github.raju6302.cifa.worker.logs;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the project's own frames in JVM stack traces ({@code at shop.PriceCalculatorTest.applies(PriceCalculatorTest.java:13)})
 * and maps them to source files.
 *
 * <p>A frame only names the class and file, so the path is derived from the standard Maven and
 * Gradle layout: {@code <module>/src/<main|test>/<language>/<package>/<File>}. Frames of test
 * classes go to {@code src/test}, everything else to {@code src/main}.
 */
final class JvmStackFrames {

    private static final Pattern FRAME = Pattern.compile(
            "^\\s{0,16}at (?:[\\w.$-]{1,100}/{1,2})?(?<class>[\\w$]{1,100}(?:\\.[\\w$]{1,100}){0,30})\\.[\\w$<>-]{1,200}"
                    + "\\((?<file>[\\w$-]{1,200}\\.(?<extension>java|kt|groovy|scala)):(?<line>\\d{1,7})\\)");
    private static final List<String> LIBRARY_PACKAGES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.", "kotlin.", "kotlinx.", "scala.", "groovy.",
            "org.codehaus.groovy.", "org.junit.", "junit.", "org.opentest4j.", "org.assertj.", "org.hamcrest.",
            "org.mockito.", "net.bytebuddy.", "org.apache.maven.", "org.gradle.", "worker.org.gradle.",
            "org.springframework.", "com.google.common.");
    private static final Pattern TEST_CLASS_NAME = Pattern.compile("(?:Test|Tests|IT|Spec)$");
    private static final int MAX_FRAMES = 5;

    private JvmStackFrames() {
    }

    /**
     * @param moduleDir   repo-relative module directory, empty for the root, or null if unknown
     * @param testClasses fully qualified names of the classes whose tests failed
     */
    static List<CodeReference> references(List<LogLine> lines, String moduleDir, Set<String> testClasses) {
        List<CodeReference> references = new ArrayList<>();
        for (LogLine line : lines) {
            Matcher frame = FRAME.matcher(line.content());
            if (!frame.find() || isLibrary(frame.group("class"))) {
                continue;
            }
            references.add(new CodeReference(path(frame, moduleDir, testClasses), Integer.valueOf(frame.group("line"))));
            if (references.size() == MAX_FRAMES) {
                break;
            }
        }
        return references;
    }

    private static String path(Matcher frame, String moduleDir, Set<String> testClasses) {
        String className = frame.group("class");
        int dollar = className.indexOf('$');
        String outerClass = dollar < 0 ? className : className.substring(0, dollar);
        int lastDot = outerClass.lastIndexOf('.');
        String packagePath = lastDot < 0 ? "" : outerClass.substring(0, lastDot).replace('.', '/') + "/";
        String simpleName = outerClass.substring(lastDot + 1);
        boolean test = testClasses.contains(outerClass) || TEST_CLASS_NAME.matcher(simpleName).find();
        String extension = frame.group("extension");
        String language = "kt".equals(extension) ? "kotlin" : extension;
        String module = moduleDir == null || moduleDir.isEmpty() ? "" : moduleDir + "/";
        return "%ssrc/%s/%s/%s%s".formatted(module, test ? "test" : "main", language, packagePath, frame.group("file"));
    }

    private static boolean isLibrary(String className) {
        return LIBRARY_PACKAGES.stream().anyMatch(className::startsWith);
    }
}
