package com.aktagon.llmkit;

import java.util.ArrayList;
import java.util.List;

/*





*/
final class MusicOptions {
    /**/
    boolean raw = false;
    /**/
    List<MiddlewareFn> middleware = new ArrayList<>();

    MusicOptions() {}

    /**/
    MusicOptions copy() {
        MusicOptions o = new MusicOptions();
        o.raw = raw;
        o.middleware = new ArrayList<>(middleware);
        return o;
    }
}
