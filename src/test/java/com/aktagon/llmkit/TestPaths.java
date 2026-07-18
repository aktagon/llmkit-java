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

/**
 * Locates repo-root artifacts (shared wire goldens, driver output) from the
 * Gradle test working directory ({@code java/}), independent of machine.
 */
final class TestPaths {
    // The response projection carries an explicit `error: null`; the shared
    // request serializer omits nulls, so artifact writing keeps them.
    private static final Gson NULL_SERIALIZING =
            new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

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

    /**
     * Write a request-wire HEADER artifact to
     * {@code target/wire/request/<fixture>/java.headers.json} (lowercased keys),
     * the per-SDK companion the comparator subset-matches against a fixture's
     * {@code <fixture>.headers.json} golden (HANDOFF-028 / BUG-017).
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

    /**
     * Write a response-wire artifact to
     * {@code target/wire/response/<shape>/java.json}, mirroring the other SDK
     * drivers' outputs for the cross-SDK response comparator.
     */
    static void writeResponseArtifact(String shape, JsonElement projection) throws IOException {
        Path directory = repoRoot().resolve("target/wire/response").resolve(shape);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("java.json"), NULL_SERIALIZING.toJson(projection), StandardCharsets.UTF_8);
    }

    /**
     * Write a lifecycle-wire artifact to
     * {@code target/wire/lifecycle/<fixture>/java.json} (the normalized
     * JobStatus projection) for the cross-SDK lifecycle comparator.
     */
    static void writeLifecycleArtifact(String fixture, JsonElement projection) throws IOException {
        Path directory = repoRoot().resolve("target/wire/lifecycle").resolve(fixture);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("java.json"), NULL_SERIALIZING.toJson(projection), StandardCharsets.UTF_8);
    }

    /**
     * Write a catalogue-wire artifact to
     * {@code target/wire/catalogue/<case>/java.json} (the {method, url,
     * headers} projection), mirroring the other SDK drivers' outputs for the
     * cross-SDK catalogue comparator (ADR-067 Fix B / CAT-006).
     */
    static void writeCatalogueArtifact(String caseName, JsonElement projection) throws IOException {
        Path directory = repoRoot().resolve("target/wire/catalogue").resolve(caseName);
        Files.createDirectories(directory);
        Files.writeString(
                directory.resolve("java.json"), Json.serialize(projection), StandardCharsets.UTF_8);
    }

    /**
     * Write a SigV4-wire artifact to
     * {@code target/wire/sigv4/<fixture>/java.json} (the {canonicalRequest,
     * stringToSign, authorization} projection), mirroring the other SDK
     * drivers' outputs for the cross-SDK SigV4 comparator (CR-002).
     */
    static void writeSigV4Artifact(String fixture, String json) throws IOException {
        Path directory = repoRoot().resolve("target/wire/sigv4").resolve(fixture);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("java.json"), json, StandardCharsets.UTF_8);
    }

    /**
     * Write a telemetry-wire artifact to
     * {@code target/wire/telemetry/<fixture>/java.json} (the OTLP payload
     * verbatim), mirroring the other SDK drivers' outputs for the cross-SDK
     * telemetry comparator (ADR-054 TEL-011).
     */
    static void writeTelemetryArtifact(String fixture, String payload) throws IOException {
        Path directory = repoRoot().resolve("target/wire/telemetry").resolve(fixture);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("java.json"), payload, StandardCharsets.UTF_8);
    }
}
