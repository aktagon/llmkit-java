package com.aktagon.llmkit;

/*












*/
public record Usage(
        long input,
        long output,
        long cacheWrite,
        long cacheRead,
        long reasoning,
        double cost) {

    /**/
    public static Usage zero() {
        return new Usage(0, 0, 0, 0, 0, 0.0);
    }
}
