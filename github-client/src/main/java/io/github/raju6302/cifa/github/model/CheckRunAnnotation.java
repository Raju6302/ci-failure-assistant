package io.github.raju6302.cifa.github.model;

/**
 * An annotation on a job's check run: problem-matcher hits (compiler errors with file and
 * line), {@code ##[error]} lines, and messages only the runner knows, such as a job
 * exceeding its {@code timeout-minutes}.
 *
 * @param path            repo-relative file, or {@code .github} when the annotation has no file
 * @param annotationLevel {@code notice}, {@code warning} or {@code failure}
 */
public record CheckRunAnnotation(
        String path,
        Integer startLine,
        Integer endLine,
        String annotationLevel,
        String title,
        String message) {

    /** GitHub uses this path for annotations that don't point at a file. */
    public static final String NO_FILE = ".github";

    public boolean isFailure() {
        return "failure".equals(annotationLevel);
    }

    public boolean hasFile() {
        return path != null && !path.isBlank() && !NO_FILE.equals(path);
    }
}
