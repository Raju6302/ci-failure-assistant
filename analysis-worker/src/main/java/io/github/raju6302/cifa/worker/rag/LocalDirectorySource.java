package io.github.raju6302.cifa.worker.rag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** Reads files from a local checkout. Used by the retrieval eval and tests. */
public class LocalDirectorySource implements RepositorySource {

    private final Path root;

    public LocalDirectorySource(Path root) {
        this.root = root;
    }

    @Override
    public void forEachFile(String repository, String ref, Long installationId, Consumer<SourceFile> visitor) {
        try (Stream<Path> files = Files.walk(root)) {
            files.filter(Files::isRegularFile).sorted().forEach(file -> {
                try {
                    visitor.accept(new SourceFile(root.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
