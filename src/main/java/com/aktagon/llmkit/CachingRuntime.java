package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Caching;
import com.aktagon.llmkit.providers.generated.Providers;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Prompt-caching runtime — a port of Rust's {@code caching.rs} / Swift's
 * {@code CachingRuntime.swift}. Dispatches on the generated
 * {@code Caching.Def.mode} (never on provider name): automatic caching is a
 * no-op (the provider caches transparently), explicit caching injects
 * {@code cache_control} onto the system prefix (Anthropic), and resource
 * caching creates a provider-side cached-content resource and references it
 * (Google).
 */
final class CachingRuntime {
    private CachingRuntime() {}

    /**
     * Apply caching to an already-built request body when the caller opted
     * in ({@code options.caching}). No-op when caching is off; a loud
     * validation error when the provider declares no caching config.
     */
    static void apply(
            JsonObject body,
            Providers.Spec config,
            String model,
            String apiKey,
            PromptOptions options,
            HttpTransport http,
            String baseUrlOverride) {
        if (!options.caching) {
            return;
        }
        Caching.Def caching = Caching.config(config.name);
        if (caching == null) {
            throw new ValidationException("caching", "not supported by " + config.slug);
        }
        switch (caching.mode) {
            case AUTOMATIC_CACHING -> { }
            case EXPLICIT_CACHING -> applyExplicit(body, caching.controlType, config);
            case RESOURCE_CACHING ->
                    applyResource(body, config, model, apiKey, options, http, baseUrlOverride);
        }
    }

    /**
     * Explicit caching (Anthropic): rewrite the system prefix into a single
     * text block carrying {@code cache_control}. Placement is config-driven
     * — the system lives at the top level (Anthropic) or as the last system
     * message.
     */
    private static void applyExplicit(JsonObject body, String controlType, Providers.Spec config) {
        switch (config.systemPlacement) {
            case "TopLevelField" -> {
                JsonElement system = body.get("system");
                if (system != null && system.isJsonPrimitive()) {
                    JsonArray wrapped = new JsonArray();
                    wrapped.add(cachedTextBlock(system.getAsString(), controlType));
                    body.add("system", wrapped);
                }
            }
            case "MessageInArray" -> {
                JsonElement messagesElement = body.get("messages");
                if (messagesElement == null || !messagesElement.isJsonArray()) {
                    return;
                }
                JsonArray messages = messagesElement.getAsJsonArray();
                for (int index = messages.size() - 1; index >= 0; index--) {
                    JsonElement messageElement = messages.get(index);
                    if (!messageElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject message = messageElement.getAsJsonObject();
                    JsonElement role = message.get("role");
                    if (role == null || !role.isJsonPrimitive() || !"system".equals(role.getAsString())) {
                        continue;
                    }
                    JsonElement content = message.get("content");
                    if (content != null && content.isJsonPrimitive()) {
                        JsonArray wrapped = new JsonArray();
                        wrapped.add(cachedTextBlock(content.getAsString(), controlType));
                        message.add("content", wrapped);
                    }
                    break;
                }
            }
            default -> { } // SiblingObject — resource caching handles Google
        }
    }

    private static JsonObject cachedTextBlock(String text, String controlType) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        JsonObject control = new JsonObject();
        control.addProperty("type", controlType);
        block.add("cache_control", control);
        return block;
    }

    /**
     * Resource caching (Google): create a {@code /cachedContents} resource
     * holding the system instruction, then reference it by name and drop the
     * inline system. Fires the {@code cacheCreate} middleware op around the
     * network hop.
     */
    private static void applyResource(
            JsonObject body,
            Providers.Spec config,
            String model,
            String apiKey,
            PromptOptions options,
            HttpTransport http,
            String baseUrlOverride) {
        Caching.Def cachingConfig = Caching.config(config.name);
        Caching.ResourceLifecycleDef lifecycle = cachingConfig != null ? cachingConfig.lifecycle : null;
        if (lifecycle == null) {
            throw new ValidationException("caching", "resource caching requires lifecycle config");
        }
        JsonElement systemInstruction = body.get("system_instruction");
        if (systemInstruction == null) {
            return;
        }

        String ttl = options.cacheTtl != null ? String.valueOf(options.cacheTtl) : cachingConfig.defaultTtl;

        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        String createUrl = base + lifecycle.createEndpoint + "?" + config.authQueryParam + "=" + apiKey;

        JsonObject createBody = new JsonObject();
        createBody.addProperty("model", "models/" + model);
        createBody.addProperty("ttl", ttl + "s");
        JsonObject userTurn = new JsonObject();
        userTurn.addProperty("role", "user");
        JsonObject part = new JsonObject();
        part.addProperty("text", "cache");
        JsonArray parts = new JsonArray();
        parts.add(part);
        userTurn.add("parts", parts);
        JsonArray contents = new JsonArray();
        contents.add(userTurn);
        createBody.add("contents", contents);
        createBody.add("systemInstruction", systemInstruction);

        Event baseEvent = Event.of(MiddlewareOp.CACHE_CREATE, config.slug, model);
        long startNanos = System.nanoTime();
        Middleware.firePre(options.middleware, baseEvent);

        String resourceId;
        try {
            Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);
            HttpTransport.Result result = http.postJson(createUrl, Json.serialize(createBody), headers);
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw ResponseParser.parseError(config, result.statusCode(), result.body());
            }
            JsonElement parsed = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
            resourceId = Json.stringAt(parsed, lifecycle.responseIdPath);
            if (resourceId.isEmpty()) {
                throw new DecodingException("cache create: empty resource ID");
            }
        } catch (RuntimeException e) {
            Middleware.firePost(
                    options.middleware,
                    baseEvent.toPost("", null, e, Middleware.elapsedMillis(startNanos)));
            throw e;
        }
        Middleware.firePost(
                options.middleware, baseEvent.toPost("", null, null, Middleware.elapsedMillis(startNanos)));

        body.add(lifecycle.referenceField, new JsonPrimitive(resourceId));
        body.remove("system_instruction");
    }
}
