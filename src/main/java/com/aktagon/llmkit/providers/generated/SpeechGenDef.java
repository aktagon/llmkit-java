// Code generated — DO NOT EDIT.

package com.aktagon.llmkit.providers.generated;

public record SpeechGenDef(
        String wireShape,
        /** "base64Envelope" | "rawBody" | "base64InteractionSteps" (ADR-051). */
        String audioResponseEncoding,
        String genEndpoint,
        java.util.List<SpeechModelDef> models) {

    /** Public, keyless per-provider config; null when the provider has no speech capability. */
    public static SpeechGenDef speechGenConfig(ProviderName provider) {
        switch (provider) {
            case GOOGLE:
                return new SpeechGenDef(
                        "SpeechGemini",
                        "base64InteractionSteps",
                        "/v1beta/interactions",
                        java.util.List.of(
                                new SpeechModelDef("gemini-3.8-flash-lite-tts", "Gemini 3.8 Flash-Lite TTS", "audio/wav", 24000, 2, true),
                                new SpeechModelDef("gemini-3.8-flash-tts", "Gemini 3.8 Flash TTS", "audio/wav", 24000, 2, true)
                        ));
            case INWORLD:
                return new SpeechGenDef(
                        "SpeechInworld",
                        "base64Envelope",
                        "/tts/v1/voice",
                        java.util.List.of(
                                new SpeechModelDef("inworld-tts-1.5-max", "Inworld TTS 1.5 Max", "audio/wav", 0, 0, false),
                                new SpeechModelDef("inworld-tts-1.5-mini", "Inworld TTS 1.5 Mini", "audio/wav", 0, 0, false),
                                new SpeechModelDef("inworld-tts-2", "Inworld TTS 2", "audio/wav", 0, 0, false)
                        ));
            case OPENAI:
                return new SpeechGenDef(
                        "SpeechOpenAI",
                        "rawBody",
                        "/v1/audio/speech",
                        java.util.List.of(
                                new SpeechModelDef("gpt-4o-mini-tts", "GPT-4o mini TTS", "audio/mpeg", 0, 0, true),
                                new SpeechModelDef("tts-1", "TTS 1", "audio/mpeg", 0, 0, false),
                                new SpeechModelDef("tts-1-hd", "TTS 1 HD", "audio/mpeg", 0, 0, false)
                        ));
            case OPENROUTER:
                return new SpeechGenDef(
                        "SpeechOpenAI",
                        "rawBody",
                        "/v1/audio/speech",
                        java.util.List.of(
                                new SpeechModelDef("elevenlabs/eleven-v3", "Eleven v3", "audio/mpeg", 0, 0, false),
                                new SpeechModelDef("elevenlabs/eleven-v4", "Eleven v4", "audio/mpeg", 0, 0, false),
                                new SpeechModelDef("elevenlabs/eleven-v4-turbo", "Eleven v4 Turbo", "audio/mpeg", 0, 0, false),
                                new SpeechModelDef("fish-audio/drama-3", "Fish Audio Drama 3", "audio/mpeg", 0, 0, false),
                                new SpeechModelDef("fish-audio/s2.1-pro", "Fish Audio S2.1 Pro", "audio/mpeg", 0, 0, false),
                                new SpeechModelDef("google/gemini-3.1-flash-tts-preview", "Gemini 3.1 Flash TTS Preview", "audio/mpeg", 0, 0, false),
                                new SpeechModelDef("google/gemini-3.8-flash-lite-tts", "Gemini 3.8 Flash Lite TTS", "audio/mpeg", 0, 0, false),
                                new SpeechModelDef("google/gemini-3.8-flash-tts", "Gemini 3.8 Flash TTS", "audio/mpeg", 0, 0, false),
                                new SpeechModelDef("hexgrad/kokoro-82m", "Kokoro 82M", "audio/mpeg", 0, 0, false),
                                new SpeechModelDef("x-ai/grok-voice-tts-1.0", "Grok Voice TTS 1.0", "audio/mpeg", 0, 0, false)
                        ));
            default: return null;
        }
    }
}
