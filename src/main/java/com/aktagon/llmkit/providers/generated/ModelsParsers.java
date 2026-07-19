//

package com.aktagon.llmkit.providers.generated;

import com.aktagon.llmkit.DecodingException;
import com.aktagon.llmkit.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/*




*/
public final class ModelsParsers {
    private ModelsParsers() {}

    /**/
    public static final class ParsedModelRecord {
        public final String id;
        public final String displayName;
        public final String description;
        public final long created;
        public final long contextWindow;
        public final long maxOutput;
        public final JsonElement raw;

        ParsedModelRecord(
                String id,
                String displayName,
                String description,
                long created,
                long contextWindow,
                long maxOutput,
                JsonElement raw) {
            this.id = id;
            this.displayName = displayName;
            this.description = description;
            this.created = created;
            this.contextWindow = contextWindow;
            this.maxOutput = maxOutput;
            this.raw = raw;
        }
    }

    /*


*/
    public static final class ParsedModelsPage {
        public final List<ParsedModelRecord> records;
        public final String nextCursor;

        ParsedModelsPage(List<ParsedModelRecord> records, String nextCursor) {
            this.records = records;
            this.nextCursor = nextCursor;
        }
    }

    /*


*/
    public static final class ModelsParseException extends RuntimeException {
        public final String provider;

        ModelsParseException(String provider, String reason) {
            super("models response decode for " + provider + ": " + reason);
            this.provider = provider;
        }
    }

    /*



*/
    private static long parseISO8601Best(String s) {
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Instant.parse(s).getEpochSecond();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }

    /**/
    public static ParsedModelsPage parseAnthropicModelsResponse(byte[] body) {
        JsonElement envelope;
        try {
            envelope = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (DecodingException e) {
            throw new ModelsParseException("anthropic", "envelope: " + e.getMessage());
        }
        List<ParsedModelRecord> records = new ArrayList<>();
        JsonElement dataElement = Json.at(envelope, "data");
        if (dataElement != null && dataElement.isJsonArray()) {
            for (JsonElement wireElement : dataElement.getAsJsonArray()) {
                if (!wireElement.isJsonObject()) {
                    continue;
                }
                JsonObject wire = wireElement.getAsJsonObject();
                long declared = Json.longAt(wire, "max_output_tokens");
                long maxOut = declared > 0 ? declared : Json.longAt(wire, "max_tokens");
                records.add(new ParsedModelRecord(
                        Json.stringAt(wire, "id"),
                        Json.stringAt(wire, "display_name"),
                        "",
                        parseISO8601Best(Json.stringAt(wire, "created_at")),
                        Json.longAt(wire, "max_input_tokens"),
                        maxOut,
                        wire));
            }
        }
        String nextCursor = "";
        JsonElement hasMoreElement = Json.at(envelope, "has_more");
        if (hasMoreElement != null && hasMoreElement.isJsonPrimitive() && hasMoreElement.getAsBoolean()) {
            nextCursor = Json.stringAt(envelope, "last_id");
        }
        return new ParsedModelsPage(records, nextCursor);
    }

    /*




*/
    public static ParsedModelsPage parseOpenAICohortModelsResponse(byte[] body) {
        JsonElement parsed;
        try {
            parsed = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (DecodingException e) {
            throw new ModelsParseException("openai-cohort", "envelope: " + e.getMessage());
        }
        JsonArray data;
        if (parsed.isJsonArray()) {
            data = parsed.getAsJsonArray();
        } else {
            JsonElement dataElement = Json.at(parsed, "data");
            data = dataElement != null && dataElement.isJsonArray() ? dataElement.getAsJsonArray() : new JsonArray();
        }
        List<ParsedModelRecord> records = new ArrayList<>();
        for (JsonElement wireElement : data) {
            if (!wireElement.isJsonObject()) {
                continue;
            }
            JsonObject wire = wireElement.getAsJsonObject();
            records.add(new ParsedModelRecord(
                    Json.stringAt(wire, "id"), "", "", Json.longAt(wire, "created"), 0, 0, wire));
        }
        return new ParsedModelsPage(records, "");
    }

    /*


*/
    public static ParsedModelsPage parseGoogleModelsResponse(byte[] body) {
        JsonElement envelope;
        try {
            envelope = Json.parse(new String(body, StandardCharsets.UTF_8));
        } catch (DecodingException e) {
            throw new ModelsParseException("google", "envelope: " + e.getMessage());
        }
        List<ParsedModelRecord> records = new ArrayList<>();
        JsonElement dataElement = Json.at(envelope, "models");
        if (dataElement != null && dataElement.isJsonArray()) {
            String prefix = "models/";
            for (JsonElement wireElement : dataElement.getAsJsonArray()) {
                if (!wireElement.isJsonObject()) {
                    continue;
                }
                JsonObject wire = wireElement.getAsJsonObject();
                String id = Json.stringAt(wire, "name");
                if (id.startsWith(prefix)) {
                    id = id.substring(prefix.length());
                }
                records.add(new ParsedModelRecord(
                        id,
                        Json.stringAt(wire, "displayName"),
                        Json.stringAt(wire, "description"),
                        0,
                        Json.longAt(wire, "inputTokenLimit"),
                        Json.longAt(wire, "outputTokenLimit"),
                        wire));
            }
        }
        String nextCursor = Json.stringAt(envelope, "nextPageToken");
        return new ParsedModelsPage(records, nextCursor);
    }
}
