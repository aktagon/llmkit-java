package com.aktagon.llmkit;

import com.google.gson.JsonElement;
import java.util.Objects;

/*















*/
public record Tool(String name, String description, JsonElement schema, Handler handler) {
    /*

*/
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
