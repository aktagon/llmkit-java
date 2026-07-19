package com.aktagon.llmkit;

import java.util.List;

/*




*/
final class Middleware {
    private Middleware() {}

    /*


*/
    static void firePre(List<MiddlewareFn> hooks, Event event) {
        for (MiddlewareFn hook : hooks) {
            RuntimeException veto = hook.apply(event);
            if (veto != null) {
                throw new MiddlewareVetoException(veto);
            }
        }
    }

    /*


*/
    static void firePost(List<MiddlewareFn> hooks, Event event) {
        for (MiddlewareFn hook : hooks) {
            hook.apply(event);
        }
    }

    /**/
    static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
