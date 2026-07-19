package com.aktagon.llmkit;

import com.google.gson.JsonElement;
import java.util.Map;

/*
























*/
public record Event(
        MiddlewareOp op,
        MiddlewarePhase phase,
        String provider,
        String model,
        String tool,
        Map<String, JsonElement> args,
        String result,
        Usage usage,
        String err,
        String errType,
        Long durationMillis) {

    /**/
    static Event of(MiddlewareOp op, String provider, String model) {
        return new Event(op, MiddlewarePhase.PRE, provider, model, "", Map.of(), "", null, null, null, null);
    }

    /*

*/
    Event withTool(String tool, Map<String, JsonElement> args) {
        Map<String, JsonElement> sealed =
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(args));
        return new Event(op, phase, provider, model, tool, sealed, result, usage, err, errType, durationMillis);
    }

    /*





*/
    Event toPost(String result, Usage usage, Throwable error, long durationMillis) {
        String err = error == null ? null : error.getMessage();
        String errType = error == null ? null : errType(error);
        return new Event(
                op, MiddlewarePhase.POST, provider, model, tool, args, result, usage, err, errType, durationMillis);
    }

    /*





*/
    private static String errType(Throwable error) {
        if (error instanceof ApiException) {
            return "api_error";
        }
        if (error instanceof ValidationException) {
            return "validation_error";
        }
        return "error";
    }
}
