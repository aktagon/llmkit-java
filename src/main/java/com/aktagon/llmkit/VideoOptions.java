package com.aktagon.llmkit;

import java.util.ArrayList;
import java.util.List;

/*





*/
final class VideoOptions {
    /**/
    String outputUri = "";
    boolean raw = false;
    /**/
    List<MiddlewareFn> middleware = new ArrayList<>();

    VideoOptions() {}

    /**/
    VideoOptions copy() {
        VideoOptions o = new VideoOptions();
        o.outputUri = outputUri;
        o.raw = raw;
        o.middleware = new ArrayList<>(middleware);
        return o;
    }
}
