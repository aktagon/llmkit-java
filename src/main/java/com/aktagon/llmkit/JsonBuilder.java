package com.aktagon.llmkit;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.Map;










final class JsonBuilder {
    private JsonBuilder() {}





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


    private static JsonObject childObject(JsonObject obj, String field) {
        JsonElement child = obj.get(field);
        if (child != null && child.isJsonObject()) {
            return child.getAsJsonObject();
        }
        JsonObject created = new JsonObject();
        obj.add(field, created);
        return created;
    }






    private static boolean isEmptyWireValue(JsonElement value) {
        if (value == null || value.isJsonNull()) {
            return true;
        }
        if (!value.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (primitive.isString()) {
            return primitive.getAsString().isEmpty();
        }
        return primitive.isNumber() && primitive.getAsDouble() == 0.0;
    }







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
