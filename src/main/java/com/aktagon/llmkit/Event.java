package com.aktagon.llmkit;

import com.google.gson.JsonElement;
import java.util.Map;

/**
 * The observation record passed to each middleware hook (ADR-001). Components
 * beyond op/provider/model/phase are populated only for the ops that carry
 * them, mirroring the generated {@code Event} struct in the other five SDKs.
 * Immutable; the runtime builds a pre-phase instance via {@link #of} and a
 * post-phase instance via {@link #toPost}/{@link #withTool} rather than
 * mutating one in place. A record with accessor access ({@code event.usage()},
 * matching {@code resp.usage()}) per the HANDOFF-036 B2 convention.
 *
 * @param op the operation being observed
 * @param phase pre or post
 * @param provider the provider slug
 * @param model the model id ("" when the op has none)
 * @param tool set only for {@link MiddlewareOp#TOOL_CALL}
 * @param args set only for {@link MiddlewareOp#TOOL_CALL}, pre phase
 * @param result set only for {@link MiddlewareOp#TOOL_CALL}, post phase
 * @param usage set for {@link MiddlewareOp#LLM_REQUEST}, post phase
 * @param err set in the post phase when the operation failed; human-readable —
 *     telemetry never re-parses it (ADR-071)
 * @param errType set in the post phase when the operation failed: one of
 *     {@code api_error} | {@code validation_error} | {@code error}, stamped
 *     structurally from the typed error at the {@link #toPost} erasure seam
 *     (ADR-071); the OTLP builder reads this verbatim
 * @param durationMillis set in the post phase (wall-clock duration of the
 *     operation, in millis)
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

    /** A minimal pre-phase event for {@code op}/{@code provider}/{@code model}. */
    static Event of(MiddlewareOp op, String provider, String model) {
        return new Event(op, MiddlewarePhase.PRE, provider, model, "", Map.of(), "", null, null, null, null);
    }

    /** Copy with the {@code tool}/{@code args} components set (TOOL_CALL, pre phase).
     * The args map is sealed here (ordered, unmodifiable copy) so no middleware
     * hook can mutate what later hooks observe. */
    Event withTool(String tool, Map<String, JsonElement> args) {
        Map<String, JsonElement> sealed =
                java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(args));
        return new Event(op, phase, provider, model, tool, sealed, result, usage, err, errType, durationMillis);
    }

    /**
     * Copy transitioned to the post phase, carrying the operation's outcome.
     * The ONE erasure seam (ADR-071 ETY-003): the typed error is erased into
     * {@code err} (its message, human-readable) and {@code errType} (the
     * machine-read kind) together, so no fire site sets either ad hoc. A null
     * {@code error} means success — both stay null.
     */
    Event toPost(String result, Usage usage, Throwable error, long durationMillis) {
        String err = error == null ? null : error.getMessage();
        String errType = error == null ? null : errType(error);
        return new Event(
                op, MiddlewarePhase.POST, provider, model, tool, args, result, usage, err, errType, durationMillis);
    }

    /**
     * Maps a typed error to the stable OTEL {@code error.type} kind carried on
     * {@code errType} (ADR-071): {@link ApiException} -> {@code api_error},
     * {@link ValidationException} -> {@code validation_error}, everything else
     * (transport, decoding, veto, unknown) -> {@code error}. Classification is
     * structural ({@code instanceof}) — never a re-parse of a message string.
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
