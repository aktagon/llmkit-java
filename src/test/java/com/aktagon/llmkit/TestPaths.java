package com.aktagon.llmkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/*


*/
final class TestPaths {
    //
    //
    private static final Gson NULL_SERIALIZING =
            new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private TestPaths() {}

    /**/
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

    /**/
    static Path testdata(String relativePath) {
        return repoRoot().resolve("codegen/testdata").resolve(relativePath);
    }

    static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    /*



*/
    static void writeRequestArtifact(String fixture, JsonElement body) throws IOException {
        Path directory = repoRoot().resolve("target/wire/request").resolve(fixture);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("java.json"), Json.serialize(body), StandardCharsets.UTF_8);
    }

    /*




*/
    static void writeRequestHeaders(String fixture, Map<String, String> headers) throws IOException {
        Path directory = repoRoot().resolve("target/wire/request").resolve(fixture);
        Files.createDirectories(directory);
        JsonObject lowered = new JsonObject();
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            sorted.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue());
        }
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            lowered.addProperty(entry.getKey(), entry.getValue());
        }
        Files.writeString(
                directory.resolve("java.headers.json"), Json.serialize(lowered), StandardCharsets.UTF_8);
    }

    /*



*/
    static void writeResponseArtifact(String shape, JsonElement projection) throws IOException {
        Path directory = repoRoot().resolve("target/wire/response").resolve(shape);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("java.json"), NULL_SERIALIZING.toJson(projection), StandardCharsets.UTF_8);
    }

    /*



*/
    static void writeLifecycleArtifact(String fixture, JsonElement projection) throws IOException {
        Path directory = repoRoot().resolve("target/wire/lifecycle").resolve(fixture);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("java.json"), NULL_SERIALIZING.toJson(projection), StandardCharsets.UTF_8);
    }

    /*




*/
    static void writeCatalogueArtifact(String caseName, JsonElement projection) throws IOException {
        Path directory = repoRoot().resolve("target/wire/catalogue").resolve(caseName);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("java.json"), Json.serialize(projection), StandardCharsets.UTF_8);
    }

    /*




*/
    static void writeSigV4Artifact(String fixture, String json) throws IOException {
        Path directory = repoRoot().resolve("target/wire/sigv4").resolve(fixture);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("java.json"), json, StandardCharsets.UTF_8);
    }

    /*




*/
    static void writeTelemetryArtifact(String fixture, String payload) throws IOException {
        Path directory = repoRoot().resolve("target/wire/telemetry").resolve(fixture);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("java.json"), payload, StandardCharsets.UTF_8);
    }
}
