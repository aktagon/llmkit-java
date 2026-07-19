//

package com.aktagon.llmkit.providers.generated;

public record MusicGenDef(
        String wireShape,
        String genEndpoint,
        java.util.List<MusicModelDef> models) {

    /**/
    public static MusicGenDef musicGenConfig(ProviderName provider) {
        switch (provider) {
            case GOOGLE:
                return new MusicGenDef(
                        "MusicGenerateContent",
                        "",
                        java.util.List.of(
                                new MusicModelDef("lyria-3-clip-preview", "Lyria 3 Clip", true, 30, "audio/mpeg", 0, java.util.List.of("audio/mpeg")),
                                new MusicModelDef("lyria-3-pro-preview", "Lyria 3 Pro", true, 120, "audio/mpeg", 0, java.util.List.of("audio/mpeg"))
                        ));
            case MINIMAX:
                return new MusicGenDef(
                        "MusicMinimax",
                        "https://api.minimax.io/v1/music_generation",
                        java.util.List.of(
                                new MusicModelDef("music-2.6", "MiniMax Music 2.6", true, 0, "audio/mpeg", 44100, java.util.List.of("audio/mpeg", "audio/wav"))
                        ));
            case VERTEX:
                return new MusicGenDef(
                        "MusicPredict",
                        "",
                        java.util.List.of(
                                new MusicModelDef("lyria-002", "Lyria 2", false, 30, "audio/wav", 48000, java.util.List.of("audio/wav"))
                        ));
            default: return null;
        }
    }
}
