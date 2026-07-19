package com.aktagon.llmkit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/*
















*/
public final class Json {
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}

    public static JsonElement parse(String text) {
        try {
            return JsonParser.parseString(text);
        } catch (RuntimeException e) {
            throw new DecodingException("response body is not valid JSON: " + e.getMessage());
        }
    }

    static String serialize(JsonElement element) {
        return GSON.toJson(element);
    }

    /*



*/
    public static JsonElement at(JsonElement root, String path) {
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

    public static String stringAt(JsonElement root, String path) {
        JsonElement found = at(root, path);
        return found != null && found.isJsonPrimitive() ? found.getAsString() : "";
    }

    public static long longAt(JsonElement root, String path) {
        JsonElement found = at(root, path);
        return found != null && found.isJsonPrimitive()
                && found.getAsJsonPrimitive().isNumber() ? found.getAsLong() : 0L;
    }

    static double doubleAt(JsonElement root, String path) {
        JsonElement found = at(root, path);
        return found != null && found.isJsonPrimitive()
                && found.getAsJsonPrimitive().isNumber() ? found.getAsDouble() : 0.0;
    }

    /**/
    static JsonObject object() {
        return new JsonObject();
    }
}
