package com.aktagon.llmkit;

import com.google.gson.JsonElement;
import java.util.Objects;

/**
 * A tool the model may invoke during an {@link Agent} loop. Handwritten (not
 * generated) because it carries a runtime handler — the behavior the ontology
 * deliberately does not describe (ADR-050: generate data, not logic). Mirror
 * of Swift's {@code Tool} / Rust's {@code types.rs::Tool}. A record with
 * accessor access ({@code tool.name()}) per the HANDOFF-036 B2 convention;
 * construction is unchanged ({@code new Tool(name, description, schema,
 * handler)}).
 *
 * @param name the tool name the model selects and that a
 *     {@code ToolCall.name} matches
 * @param description a human-readable description the model uses to decide
 *     when to call it
 * @param schema the JSON-Schema of the tool's arguments, embedded verbatim
 *     into the provider-specific tool-definition wire shape
 * @param handler the executor
 */
public record Tool(String name, String description, JsonElement schema, Handler handler) {
    /** The executor: receives the model-supplied argument object and returns
     * the stringified result (or throws, surfaced to the model as an error
     * string). */
    public interface Handler {
        String run(JsonElement args) throws Exception;
    }

    public Tool {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(handler, "handler");
    }
}
