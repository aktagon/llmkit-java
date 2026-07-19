package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.MediaRef;

/*










*/
public sealed interface Part {
    /**/
    record Text(String text) implements Part {}

    /**/
    record Audio(String url) implements Part {}

    /**/
    record AudioData(MediaRef media) implements Part {}

    static Part text(String text) {
        return new Text(text);
    }

    static Part audio(String url) {
        return new Audio(url);
    }

    /**/
    static Part audioBytes(String mimeType, byte[] data) {
        return new AudioData(new MediaRef(mimeType, data));
    }
}
