package com.aktagon.llmkit;

import com.google.gson.JsonElement;

/**
 * A tool the model may invoke during an {@link Agent} loop. Handwritten (not
 * generated) because it carries a runtime handler — the behavior the ontology
 * deliberately does not describe (ADR-050: generate data, not logic). Mirror
 * of Swift's {@code Tool} / Rust's {@code types.rs::Tool}.
 */
public final class Tool {
    /** The executor: receives the model-supplied argument object and returns
     * the stringified result (or throws, surfaced to the model as an error
     * string). */
    public interface Handler {
        String run(JsonElement args) throws Exception;
    }

    /** The tool name the model selects and that a {@code ToolCall.name} matches. */
    public final String name;

    /** A human-readable description the model uses to decide when to call it. */
    public final String description;

    /** The JSON-Schema of the tool's arguments, embedded verbatim into the
     * provider-specific tool-definition wire shape. */
    public final JsonElement schema;

    public final Handler handler;

    public Tool(String name, String description, JsonElement schema, Handler handler) {
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.handler = handler;
    }
}
