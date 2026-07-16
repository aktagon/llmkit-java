package com.aktagon.llmkit;

import com.google.gson.JsonElement;







public final class Tool {



    public interface Handler {
        String run(JsonElement args) throws Exception;
    }


    public final String name;


    public final String description;



    public final JsonElement schema;

    public final Handler handler;

    public Tool(String name, String description, JsonElement schema, Handler handler) {
        this.name = name;
        this.description = description;
        this.schema = schema;
        this.handler = handler;
    }
}
