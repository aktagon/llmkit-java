package com.aktagon.llmkit;

import com.google.gson.JsonElement;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates repo-root artifacts (shared wire goldens, driver output) from the
 * Gradle test working directory ({@code java/}), independent of machine.
 */
final class TestPaths {
    private TestPaths() {}

    /** The monorepo root: the nearest ancestor containing {@code codegen/}. */
    static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("codegen"))
                    && Files.isDirectory(dir.resolve("ontology"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("monorepo root not found above " + Path.of("").toAbsolutePath());
    }

    /** A path under {@code codegen/testdata/}. */
    static Path testdata(String relativePath) {
        return repoRoot().resolve("codegen/testdata").resolve(relativePath);
    }

    static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /**
     * Write a request-wire artifact to
     * {@code target/wire/request/<fixture>/java.json}, mirroring the other
     * SDK drivers' outputs for the cross-SDK comparator.
     */
    static void writeRequestArtifact(String fixture, JsonElement body) throws IOException {
        Path directory = repoRoot().resolve("target/wire/request").resolve(fixture);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("java.json"), Json.serialize(body), StandardCharsets.UTF_8);
    }
}
