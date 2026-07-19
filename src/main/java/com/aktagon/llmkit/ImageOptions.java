package com.aktagon.llmkit;

import java.util.ArrayList;
import java.util.List;

/*





*/
final class ImageOptions {
    String aspectRatio;
    String imageSize;
    boolean includeText = false;
    /**/
    String quality;
    /**/
    String outputFormat;
    /**/
    String background;
    /**/
    Integer count;
    /**/
    List<MiddlewareFn> middleware = new ArrayList<>();

    ImageOptions() {}

    /**/
    ImageOptions copy() {
        ImageOptions o = new ImageOptions();
        o.aspectRatio = aspectRatio;
        o.imageSize = imageSize;
        o.includeText = includeText;
        o.quality = quality;
        o.outputFormat = outputFormat;
        o.background = background;
        o.count = count;
        o.middleware = new ArrayList<>(middleware);
        return o;
    }
}
