package com.aktagon.llmkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

/**
 * Mutation helpers over Gson's insertion-ordered {@link JsonObject} used while
 * constructing a request body. These re-express the operations Rust's
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
