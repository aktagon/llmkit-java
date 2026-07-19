package com.aktagon.llmkit;

import java.util.ArrayList;
import java.util.List;

/*





*/
final class UploadOptions {
    String path;
    byte[] bytes;
    String filename;
    String mimeType;
    /**/
    List<MiddlewareFn> middleware = new ArrayList<>();

    UploadOptions() {}

    /**/
    UploadOptions copy() {
        UploadOptions o = new UploadOptions();
        o.path = path;
        o.bytes = bytes;
        o.filename = filename;
        o.mimeType = mimeType;
        o.middleware = new ArrayList<>(middleware);
        return o;
    }
}
