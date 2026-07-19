package com.aktagon.llmkit;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;

/*







*/
final class JsonBuilder {
    private JsonBuilder() {}

    /*


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

    /*




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

    /*



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
