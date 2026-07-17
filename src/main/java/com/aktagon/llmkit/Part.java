package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.MediaRef;

/**
 * The shared multimodal input atom (the API golden rule, CLAUDE.md:
 * single-turn capabilities take {@code List<Part>}, not {@code
 * List<Message>}). Slice 1 carries only the variants transcription needs --
 * text plus the two audio sources (ADR-048 / ADR-051) -- mirroring Rust's
 * {@code Part::audio} / {@code Part::audio_bytes} and Swift's {@code Part}
 * enum. No public {@code Part} existed before this slice -- only the
 * generated {@link MediaRef}. {@link #audio} is a remote URL (AssemblyAI
 * ingests a public URL); {@link #audioBytes} is inline bytes (OpenAI
 * multipart ingests raw bytes). More modalities join as later capabilities
 * land on this container.
 */
public sealed interface Part {
    /** Plain text input. */
    record Text(String text) implements Part {}

    /** A remote audio URL (AssemblyAI). */
    record Audio(String url) implements Part {}

    /** Inline audio bytes (OpenAI multipart). Carries the IANA mime + raw bytes. */
    record AudioData(MediaRef media) implements Part {}

    static Part text(String text) {
        return new Text(text);
    }

    static Part audio(String url) {
        return new Audio(url);
    }

    /** Inline audio bytes from a mime type + raw bytes. Mirror of Rust {@code Part::audio_bytes}. */
    static Part audioBytes(String mimeType, byte[] data) {
        return new AudioData(new MediaRef(mimeType, data));
    }
}
