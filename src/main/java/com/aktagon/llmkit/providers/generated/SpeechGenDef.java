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
            default: return null;
        }
    }
}
