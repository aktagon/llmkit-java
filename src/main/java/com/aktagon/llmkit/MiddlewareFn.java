package com.aktagon.llmkit;

/*



*/
@FunctionalInterface
public interface MiddlewareFn {
    RuntimeException apply(Event event);
}
