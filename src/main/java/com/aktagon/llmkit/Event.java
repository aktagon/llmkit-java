package com.aktagon.llmkit;

import com.google.gson.JsonElement;
import java.util.Map;

/**
 * The observation record passed to each middleware hook (ADR-001). Fields
 * beyond op/provider/model/phase are populated only for the ops that carry
 * them, mirroring the generated {@code Event} struct in the other five SDKs.
 * Immutable; the runtime builds a pre-phase instance via {@link #of} and a
 * post-phase instance via {@link #toPost}/{@link #withTool} rather than
 * mutating one in place.
 */
public final class Event {
    public final MiddlewareOp op;
    public final MiddlewarePhase phase;
    public final String provider;
    public final String model;
    /** Set only for {@link MiddlewareOp#TOOL_CALL}. */
    public final String tool;
    /** Set only for {@link MiddlewareOp#TOOL_CALL}, pre phase. */
    public final Map<String, JsonElement> args;
    /** Set only for {@link MiddlewareOp#TOOL_CALL}, post phase. */
    public final String result;
    /** Set for {@link MiddlewareOp#LLM_REQUEST}, post phase. */
    public final Usage usage;
    /** Set in the post phase when the operation failed. */
    public final String err;
    /** Set in the post phase (wall-clock duration of the operation, in millis). */
    public final Long durationMillis;

    Event(
            MiddlewareOp op,
            MiddlewarePhase phase,
            String provider,
            String model,
            String tool,
            Map<String, JsonElement> args,
            String result,
            Usage usage,
            String err,
            Long durationMillis) {
        this.op = op;
        this.phase = phase;
        this.provider = provider;
        this.model = model;
        this.tool = tool;
        this.args = args;
        this.result = result;
        this.usage = usage;
        this.err = err;
        this.durationMillis = durationMillis;
    }

    /** A minimal pre-phase event for {@code op}/{@code provider}/{@code model}. */
    static Event of(MiddlewareOp op, String provider, String model) {
        return new Event(op, MiddlewarePhase.PRE, provider, model, "", Map.of(), "", null, null, null);
    }

    /** Copy with the {@code tool}/{@code args} fields set (TOOL_CALL, pre phase).
     * The args map is sealed here (ordered, unmodifiable copy) so no middleware
     * hook can mutate what later hooks observe. */
    Event withTool(String tool, Map<String, JsonElement> args) {
        Map<String, JsonElement> sealed =
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(args));
        return new Event(op, phase, provider, model, tool, sealed, result, usage, err, durationMillis);
    }

    /** Copy transitioned to the post phase, carrying the operation's outcome. */
    Event toPost(String result, Usage usage, String err, long durationMillis) {
        return new Event(op, MiddlewarePhase.POST, provider, model, tool, args, result, usage, err, durationMillis);
    }
}
