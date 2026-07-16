package com.aktagon.llmkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The JSON waist (ADR-068 JAVA-002): one shared Gson instance plus
 * dotted-path readers over its ordered tree model.
 *
 * <p>The instance is built with {@code disableHtmlEscaping()} — Gson's
 * default escapes {@code <}, {@code >}, {@code &}, {@code =}, {@code '} as
 * {@code \\uXXXX}, which would break byte-parity with the other SDKs on the
 * wire goldens. Serialization is compact; {@code JsonObject} preserves
 * insertion order and {@code LazilyParsedNumber} keeps the lexical form of
 * parsed numbers, so parse/serialize round-trips are deterministic.
 */
final class Json {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}

    static JsonElement parse(String text) {
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new DecodingException("response body is not valid JSON: " + e.getMessage());
        }
    }

    static String serialize(JsonElement element) {
        return GSON.toJson(element);
    }

    /**
     * Resolve a dotted path with optional array indices (e.g.
     * {@code choices[0].message.content}) against a JSON tree. Returns null
     * when any segment is absent — callers map that to their zero value.
     */
    static JsonElement at(JsonElement root, String path) {
        JsonElement current = root;
        for (String segment : path.split("\\.")) {
            int bracket = segment.indexOf('[');
            String key = bracket >= 0 ? segment.substring(0, bracket) : segment;
            if (!key.isEmpty()) {
                if (current == null || !current.isJsonObject()) {
                    return null;
                }
                current = current.getAsJsonObject().get(key);
            }
            while (bracket >= 0) {
                int close = segment.indexOf(']', bracket);
                int index = Integer.parseInt(segment.substring(bracket + 1, close));
                if (current == null || !current.isJsonArray()
                        || index >= current.getAsJsonArray().size()) {
                    return null;
                }
                current = current.getAsJsonArray().get(index);
                bracket = segment.indexOf('[', close);
            }
        }
        return current;
    }

    static String stringAt(JsonElement root, String path) {
        JsonElement found = at(root, path);
        return found != null && found.isJsonPrimitive() ? found.getAsString() : "";
    }

    static long longAt(JsonElement root, String path) {
        JsonElement found = at(root, path);
        return found != null && found.isJsonPrimitive()
                && found.getAsJsonPrimitive().isNumber() ? found.getAsLong() : 0L;
    }

    static double doubleAt(JsonElement root, String path) {
        JsonElement found = at(root, path);
        return found != null && found.isJsonPrimitive()
                && found.getAsJsonPrimitive().isNumber() ? found.getAsDouble() : 0.0;
    }

    /** An empty insertion-ordered object (the request-body builder's root). */
    static JsonObject object() {
        return new JsonObject();
    }
}
