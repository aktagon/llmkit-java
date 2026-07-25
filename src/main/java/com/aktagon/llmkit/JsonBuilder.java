package com.aktagon.llmkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;

/**
 * Mutation helpers over Gson's insertion-ordered {@link JsonObject} used while
 * constructing a JSON body. These re-express the operations Rust's
 * {@code request.rs} performs on {@code serde_json::Map} — nested insert, merge
 * into parent, deep merge — mirroring Swift's {@code JSONObject} helpers
 * (which operate over an ordered-pair waist). Gson's {@code JsonObject} already
 * preserves insertion order, so these mutate the tree in place. Intermediate
 * objects are created on demand.
 */
final class JsonBuilder {
    private JsonBuilder() {}

    /**
     * Insert {@code value} at a dotted {@code path}, creating (or replacing a
     * non-object) intermediate objects — mirror of {@code insert_nested_field}.
     */
    static void setNested(JsonObject obj, String path, JsonElement value) {
        String[] parts = path.split("\\.");
        JsonObject current = obj;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement child = current.get(parts[i]);
            JsonObject childObj;
            if (child != null && child.isJsonObject()) {
                childObj = child.getAsJsonObject();
            } else {
                childObj = new JsonObject();
                current.add(parts[i], childObj);
            }
            current = childObj;
        }
        current.add(parts[parts.length - 1], value);
    }

    /**
     * Place {@code value} at a dotted {@code path} with array index support
     * ({@code choices[0].message.content}), creating intermediate objects and
     * array elements as it descends. It is the navigate-or-create inverse of
     * {@link Json#at} and walks the identical generated path strings (ADR-076
     * SYM-005). Unlike {@link #setNested}, ANY segment may be indexed —
     * Google's response text path is {@code candidates[0].content.parts[0].text},
     * two array levels created in one descent.
     *
     * <p>An empty path (the provider declares no location for this field) or an
     * empty value is a no-op: there is nothing to write, and materializing a
     * zero would invent a field the provider never sent.
     */
    static void setWirePath(JsonObject obj, String path, JsonElement value) {
        if (path.isEmpty() || isEmptyWireValue(value)) {
            return;
        }
        String[] parts = path.split("\\.");
        JsonObject current = obj;
        for (int i = 0; i < parts.length; i++) {
            boolean last = i == parts.length - 1;
            String part = parts[i];
            int bracket = part.indexOf('[');
            if (bracket < 0) {
                if (last) {
                    current.add(part, value);
                    return;
                }
                current = childObject(current, part);
                continue;
            }
            String field = part.substring(0, bracket);
            int index = Integer.parseInt(part.substring(bracket + 1, part.length() - 1));
            JsonElement existing = current.get(field);
            JsonArray items = existing != null && existing.isJsonArray()
                    ? existing.getAsJsonArray()
                    : new JsonArray();
            current.add(field, items);
            while (items.size() <= index) {
                items.add(JsonNull.INSTANCE);
            }
            if (last) {
                items.set(index, value);
                return;
            }
            JsonElement element = items.get(index);
            if (element != null && element.isJsonObject()) {
                current = element.getAsJsonObject();
            } else {
                current = new JsonObject();
                items.set(index, current);
            }
        }
    }

    /** {@code obj[field]} as an object, creating it when absent or mistyped. */
    private static JsonObject childObject(JsonObject obj, String field) {
        JsonElement child = obj.get(field);
        if (child != null && child.isJsonObject()) {
            return child.getAsJsonObject();
        }
        JsonObject created = new JsonObject();
        obj.add(field, created);
        return created;
    }

    /**
     * Whether {@code value} is the zero of its canonical type. Empty values are
     * skipped rather than written, so the encoder never claims a provider
     * reported zero tokens when the canonical {@code Response} simply had none.
     */
    /**
     * Whether {@code value} carries nothing to write. A JSON null (or a Java
     * null) is the caller saying the field was never reported, so there is no
     * location to fill.
     *
     * <p>A numeric zero is NOT empty (ADR-081). It used to be: the encoder
     * dropped every zero, so a body that explicitly said {@code cached_tokens:
     * 0} round-tripped to one that omitted the field — the reader then had to
     * guess, and guessed zero, which happened to look right. Now absence
     * arrives as null and a reported zero arrives as {@code 0}, so the encoder
     * can tell them apart instead of inferring one from the other.
     */
    private static boolean isEmptyWireValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return true;
        }
        if (!value.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isString() && primitive.getAsString().isEmpty();
    }

    /**
     * An unreported canonical field becomes JSON null, which
     * {@code setWirePath} skips; a reported value — zero included — becomes a
     * real JSON value and is written.
     */
    static JsonElement wire(Number value) {
        return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
    }

    /** {@code wire(Number)} for the optional canonical signal strings. */
    static JsonElement wire(String value) {
        return value == null ? JsonNull.INSTANCE : new JsonPrimitive(value);
    }

    /**
     * Merge {@code extras} into the object that CONTAINS the leaf of
     * {@code path}: for {@code "a.b.c"} they land in {@code obj["a"]["b"]}; for a
     * top-level path, in {@code obj}. No-op when an intermediate is missing or
     * non-object. Mirror of {@code merge_into_parent}.
     */
    static void mergeIntoParent(JsonObject obj, String path, JsonObject extras) {
        String[] parts = path.split("\\.");
        JsonObject current = obj;
        for (int i = 0; i < parts.length - 1; i++) {
            JsonElement child = current.get(parts[i]);
            if (child == null || !child.isJsonObject()) {
                return;
            }
            current = child.getAsJsonObject();
        }
        for (Map.Entry<String, JsonElement> entry : extras.entrySet()) {
            current.add(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Deep-merge {@code src} into {@code dst}: when both hold an object at the
     * same key the objects merge, else {@code src} overwrites. Mirror of
     * {@code deep_merge}.
     */
    static void deepMerge(JsonObject dst, JsonObject src) {
        for (Map.Entry<String, JsonElement> entry : src.entrySet()) {
            JsonElement value = entry.getValue();
            JsonElement existing = dst.get(entry.getKey());
            if (value.isJsonObject() && existing != null && existing.isJsonObject()) {
                deepMerge(existing.getAsJsonObject(), value.getAsJsonObject());
            } else {
                dst.add(entry.getKey(), value);
            }
        }
    }
}
