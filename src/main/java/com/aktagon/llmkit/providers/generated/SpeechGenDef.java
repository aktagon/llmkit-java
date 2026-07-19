//

package com.aktagon.llmkit.providers.generated;

public record SpeechGenDef(
        String wireShape,
        /**/
        String audioResponseEncoding,
        String genEndpoint,
        java.util.List<String> voices,
        java.util.List<SpeechModelDef> models) {

    /**/
    public static SpeechGenDef speechGenConfig(ProviderName provider) {
        switch (provider) {
            case INWORLD:
                return new SpeechGenDef(
                        "SpeechInworld",
                        "base64Envelope",
                        "/tts/v1/voice",
                        java.util.List.of("Alex", "Ashley", "Dennis"),
                        java.util.List.of(
                                new SpeechModelDef("inworld-tts-1.5-max", "Inworld TTS 1.5 Max", "audio/wav", 0),
                                new SpeechModelDef("inworld-tts-1.5-mini", "Inworld TTS 1.5 Mini", "audio/wav", 0),
                                new SpeechModelDef("inworld-tts-2", "Inworld TTS 2", "audio/wav", 0)
                        ));
            case OPENAI:
                return new SpeechGenDef(
                        "SpeechOpenAI",
                        "rawBody",
                        "/v1/audio/speech",
                        java.util.List.of("alloy", "ash", "ballad", "coral", "echo", "fable", "nova", "onyx", "sage", "shimmer"),
                        java.util.List.of(
                                new SpeechModelDef("gpt-4o-mini-tts", "GPT-4o mini TTS", "audio/mpeg", 0),
                                new SpeechModelDef("tts-1", "TTS 1", "audio/mpeg", 0),
                                new SpeechModelDef("tts-1-hd", "TTS 1 HD", "audio/mpeg", 0)
                        ));
            default: return null;
        }
    }
}
