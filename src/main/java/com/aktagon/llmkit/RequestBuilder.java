package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Options;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Request;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.util.LinkedHashMap;
import java.util.Map;

/*







*/
final class RequestBuilder {
    private RequestBuilder() {}

    /**/
    record Resolved(String wireShape, String endpoint) {}

    /**/
    record Built(JsonObject body, Map<String, String> headers) {}

    /*




*/
    static Resolved resolveChatProtocol(Providers.Spec config, String token) {
        if (token == null || token.isEmpty()) {
            return new Resolved(config.chatWireShape, config.endpoint);
        }
        String want = protocolWireShape(token);
        if (want == null) {
            throw new ValidationException("protocol", "unknown protocol: " + token);
        }
        for (Providers.ChatProtocol proto : config.chatProtocols) {
            if (proto.wireShape.equals(want)) {
                return new Resolved(proto.wireShape, proto.endpoint);
            }
        }
        throw new ValidationException(
                "protocol", "provider " + config.slug + " does not support protocol \"" + token + "\"");
    }

    private static String protocolWireShape(String token) {
        return "responses".equals(token) ? "ChatResponsesOpenAI" : null;
    }

    /*




*/
    static Built buildBody(
            Providers.Spec config,
            String wireShape,
            String apiKey,
            String model,
            String system,
            java.util.List<Msg> msgs,
            java.util.List<Tool> tools,
            PromptOptions options) {
        validateOptions(config, options);

        JsonObject body = Json.object();
        Map<String, String> headers = buildAuthHeaders(config, apiKey);

        if (config.modelInBody) {
            body.addProperty("model", model);
        }

        int maxTokens = options.maxTokens != null ? options.maxTokens : config.defaultMaxTokens;
        String maxKey = resolveOptionKey(config.name, model, Options.Key.MAX_TOKENS);
        if (maxKey != null) {
            body.add(maxKey, new JsonPrimitive(maxTokens));
        }

        //
        switch (config.systemPlacement) {
            case "TopLevelField" -> {
                if (system != null) {
                    if ("ChatBedrock".equals(wireShape)) {
                        JsonObject block = new JsonObject();
                        block.addProperty("text", system);
                        JsonArray arr = new JsonArray();
                        arr.add(block);
                        body.add("system", arr);
                    } else {
                        body.addProperty("system", system);
                    }
                }
            }
            case "SiblingObject" -> {
                if (system != null) {
                    JsonObject part = new JsonObject();
                    part.addProperty("text", system);
                    JsonArray parts = new JsonArray();
                    parts.add(part);
                    JsonObject partsWrapper = new JsonObject();
                    partsWrapper.add("parts", parts);
                    body.add("system_instruction", partsWrapper);
                }
            }
            default -> { } // MessageInArray
        }

        Transforms.applyMessageShape(body, msgs, system, wireShape, config);
        Transforms.applyToolDefs(body, config, tools);

        //
        //
        //
        if (!config.wrapsOptionsIn.isEmpty()) {
            JsonObject wrapped = new JsonObject();
            JsonObject rootExtras = addOptions(wrapped, config.name, model, options);
            if (maxKey != null) {
                JsonBuilder.setNested(wrapped, maxKey, new JsonPrimitive(maxTokens));
                body.remove(maxKey);
            }
            if (wrapped.size() > 0) {
                body.add(config.wrapsOptionsIn, wrapped);
            }
            JsonBuilder.deepMerge(body, rootExtras);
        } else {
            JsonObject rootExtras = addOptions(body, config.name, model, options);
            JsonBuilder.deepMerge(body, rootExtras);
        }

        if (!config.safetySettingsWirePath.isEmpty() && !options.safetySettings.isEmpty()) {
            JsonArray settings = new JsonArray();
            for (SafetySetting setting : options.safetySettings) {
                JsonObject entry = new JsonObject();
                entry.addProperty("category", setting.category());
                entry.addProperty("threshold", setting.threshold());
                settings.add(entry);
            }
            body.add(config.safetySettingsWirePath, settings);
        }

        if (options.schema != null) {
            addStructuredOutput(body, headers, options.schema, config.name);
        }

        //
        //
        //
        //
        //
        //
        if (Transforms.hasFileParts(msgs)) {
            Request.FileUploadDef upload = Request.fileUploadConfig(config.name);
            if (upload != null && !upload.betaHeader.isEmpty()) {
                String existing = headers.get("anthropic-beta");
                headers.put(
                        "anthropic-beta",
                        existing != null ? appendBeta(existing, upload.betaHeader) : upload.betaHeader);
            }
        }

        //
        //
        if ("ChatResponsesOpenAI".equals(wireShape) && body.has("max_tokens")) {
            JsonElement value = body.get("max_tokens");
            body.remove("max_tokens");
            body.add("max_output_tokens", value);
        }

        return new Built(body, headers);
    }

    /*


*/
    static String resolveModel(Providers.Spec config, String override) {
        if (override != null) {
            return override;
        }
        if (config.defaultModel.isEmpty()) {
            throw new ValidationException(
                    "model", "no model chosen and \"" + config.slug + "\" declares no default");
        }
        return config.defaultModel;
    }

    /*



*/
    static HttpTransport.Result send(
            Providers.Spec config,
            String url,
            JsonObject body,
            Map<String, String> headers,
            String apiKey,
            HttpTransport http) {
        if (!"SigV4".equals(config.authScheme)) {
            return http.postJson(url, Json.serialize(body), headers);
        }
        String payload = Json.serialize(body);
        Map<String, String> merged = sigV4Headers(
                config, "POST", url,
                payload.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json", apiKey, headers);
        return http.postJson(url, payload, merged);
    }

    /*





*/
    static Map<String, String> sigV4Headers(
            Providers.Spec config,
            String method,
            String url,
            byte[] payload,
            String contentType,
            String apiKey,
            Map<String, String> headers) {
        String region = System.getenv(config.regionEnvVar);
        if (region == null) {
            throw new ValidationException("provider", "missing env var " + config.regionEnvVar);
        }
        String secretKey = System.getenv(config.secretKeyEnvVar);
        if (secretKey == null) {
            throw new ValidationException("provider", "missing env var " + config.secretKeyEnvVar);
        }
        String sessionToken = config.sessionTokenEnvVar.isEmpty()
                ? ""
                : java.util.Objects.requireNonNullElse(System.getenv(config.sessionTokenEnvVar), "");
        Map<String, String> signed = SigV4.sign(
                method, url, payload, apiKey, secretKey, sessionToken, region, config.serviceName, contentType);
        Map<String, String> merged = new LinkedHashMap<>(signed);
        merged.putAll(headers);
        return merged;
    }

    /*


*/
    static String appendBeta(String existing, String value) {
        java.util.List<String> tokens = new java.util.ArrayList<>();
        for (String source : new String[] {existing, value}) {
            for (String token : source.split(",")) {
                String trimmed = token.trim();
                if (!trimmed.isEmpty() && !tokens.contains(trimmed)) {
                    tokens.add(trimmed);
                }
            }
        }
        return String.join(",", tokens);
    }

    /*


*/
    static Map<String, String> buildAuthHeaders(Providers.Spec config, String apiKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        switch (config.authScheme) {
            case "BearerToken" -> headers.put(config.authHeader, config.authPrefix + " " + apiKey);
            case "HeaderAPIKey" -> headers.put(config.authHeader, apiKey);
            default -> { } // QueryParamKey / SigV4
        }
        if (!config.requiredHeader.isEmpty()) {
            headers.put(config.requiredHeader, config.requiredHeaderValue);
        }
        return headers;
    }

    /*



*/
    static String buildUrl(
            Providers.Spec config, String endpoint, String apiKey, String model, String baseUrlOverride) {
        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        if (!config.regionEnvVar.isEmpty()) {
            String region = System.getenv(config.regionEnvVar);
            if (region != null) {
                base = base.replace("{region}", region);
            }
        }
        String resolved = endpoint.replace("{model}", model).replace("{apiKey}", apiKey);
        if ("QueryParamKey".equals(config.authScheme)) {
            String separator = resolved.contains("?") ? "&" : "?";
            resolved += separator + config.authQueryParam + "=" + apiKey;
        }
        return base + resolved;
    }

    //

    /*




*/
    static void validateOptions(Providers.Spec config, PromptOptions options) {
        java.util.List<Options.SupportedDef> supported = Options.supported(config.name);
        if (supported.isEmpty()) {
            return;
        }
        if (options.topK != null) {
            requireSupported(supported, Options.Key.TOP_K, "top_k", config.slug);
        }
        if (options.seed != null) {
            requireSupported(supported, Options.Key.SEED, "seed", config.slug);
        }
        if (options.frequencyPenalty != null) {
            requireSupported(supported, Options.Key.FREQUENCY_PENALTY, "frequency_penalty", config.slug);
        }
        if (options.presencePenalty != null) {
            requireSupported(supported, Options.Key.PRESENCE_PENALTY, "presence_penalty", config.slug);
        }
        if (options.thinkingBudget != null) {
            requireSupported(supported, Options.Key.THINKING_BUDGET, "thinking_budget", config.slug);
        }
        if (options.reasoningEffort != null && !options.reasoningEffort.isEmpty()) {
            requireSupported(supported, Options.Key.REASONING_EFFORT, "reasoning_effort", config.slug);
            //
            //
            for (Options.OptionOverrideDef override : Options.optionOverrides(config.name)) {
                if (override.key == Options.Key.REASONING_EFFORT
                        && !override.allowedValues.isEmpty()
                        && !override.allowedValues.contains(options.reasoningEffort)) {
                    throw new ValidationException(
                            "reasoning_effort",
                            "invalid value \"" + options.reasoningEffort + "\", must be one of: "
                                    + String.join(",", override.allowedValues));
                }
            }
        }
    }

    private static void requireSupported(
            java.util.List<Options.SupportedDef> supported, Options.Key key, String field, String slug) {
        for (Options.SupportedDef def : supported) {
            if (def.key == key) {
                return;
            }
        }
        throw new ValidationException(field, "not supported by " + slug);
    }

    /*



*/
    private static JsonObject addOptions(
            JsonObject body, ProviderName provider, String model, PromptOptions options) {
        JsonObject rootExtras = new JsonObject();
        maybeInsert(body, provider, model, Options.Key.TEMPERATURE, num(options.temperature), rootExtras);
        maybeInsert(body, provider, model, Options.Key.TOP_P, num(options.topP), rootExtras);
        maybeInsert(body, provider, model, Options.Key.TOP_K, num(options.topK), rootExtras);
        maybeInsert(body, provider, model, Options.Key.SEED, num(options.seed), rootExtras);
        maybeInsert(
                body, provider, model, Options.Key.FREQUENCY_PENALTY, num(options.frequencyPenalty), rootExtras);
        maybeInsert(
                body, provider, model, Options.Key.PRESENCE_PENALTY, num(options.presencePenalty), rootExtras);
        maybeInsert(body, provider, model, Options.Key.THINKING_BUDGET, num(options.thinkingBudget), rootExtras);
        maybeInsert(body, provider, model, Options.Key.REASONING_EFFORT, str(options.reasoningEffort), rootExtras);
        if (!options.stopSequences.isEmpty()) {
            JsonArray arr = new JsonArray();
            for (String value : options.stopSequences) {
                arr.add(value);
            }
            maybeInsert(body, provider, model, Options.Key.STOP_SEQUENCES, arr, rootExtras);
        }
        return rootExtras;
    }

    private static void maybeInsert(
            JsonObject body,
            ProviderName provider,
            String model,
            Options.Key key,
            JsonElement value,
            JsonObject rootExtras) {
        if (value == null) {
            return;
        }
        String jsonKey = resolveOptionKey(provider, model, key);
        if (jsonKey == null) {
            return;
        }
        JsonBuilder.setNested(body, jsonKey, value);

        //
        //
        //
        for (Options.OptionOverrideDef override : Options.optionOverrides(provider)) {
            if (override.key == key && !override.extraFieldsJson.isEmpty()) {
                JsonElement extras = Json.parse(override.extraFieldsJson);
                if (extras.isJsonObject()) {
                    JsonBuilder.mergeIntoParent(body, jsonKey, extras.getAsJsonObject());
                }
                break;
            }
        }
        //
        //
        for (Options.OptionOverrideDef override : Options.optionOverrides(provider)) {
            if (override.key == key && !override.rootExtraFieldsJson.isEmpty()) {
                JsonElement extras = Json.parse(override.rootExtraFieldsJson);
                if (extras.isJsonObject()) {
                    JsonBuilder.deepMerge(rootExtras, extras.getAsJsonObject());
                }
                break;
            }
        }
    }

    /*





*/
    static String resolveOptionKey(ProviderName provider, String model, Options.Key key) {
        String bestKey = null;
        int bestLen = -1;
        for (Options.ModelOptionOverrideDef override : Options.modelOptionOverrides(provider)) {
            if (override.key != key) {
                continue;
            }
            if ("id".equals(override.matcherKind)) {
                if (override.matcherValue.equals(model)) {
                    return override.jsonKey;
                }
            } else {
                String prefix = override.matcherValue.endsWith("*")
                        ? override.matcherValue.substring(0, override.matcherValue.length() - 1)
                        : override.matcherValue;
                if (model.startsWith(prefix) && prefix.length() > bestLen) {
                    bestKey = override.jsonKey;
                    bestLen = prefix.length();
                }
            }
        }
        if (bestLen >= 0) {
            return bestKey;
        }
        for (Options.SupportedDef supported : Options.supported(provider)) {
            if (supported.key == key) {
                return supported.jsonKey;
            }
        }
        return null;
    }

    //

    private static void addStructuredOutput(
            JsonObject body, Map<String, String> headers, String schema, ProviderName provider) {
        Request.StructuredOutputDef def = Request.structuredOutput(provider);
        if (def == null) {
            return;
        }
        JsonElement parsedElement = Json.parse(schema);
        if (!parsedElement.isJsonObject()) {
            return;
        }
        JsonObject parsed = parsedElement.getAsJsonObject();

        if (def.enforceStrict) {
            setAdditionalPropertiesFalse(parsed);
        }
        if (def.removeAdditionalProps) {
            removeAdditionalProperties(parsed);
        }
        if (!def.betaHeader.isEmpty()) {
            headers.put("anthropic-beta", def.betaHeader);
        }

        if ("SiblingOfFormat".equals(def.schemaPlacement)) {
            JsonBuilder.setNested(body, def.formatField, new JsonPrimitive(def.formatType));
            JsonBuilder.setNested(body, def.schemaPath, parsed);
            return;
        }

        String[] pathParts = def.schemaPath.split("\\.");
        JsonObject formatObject = new JsonObject();
        formatObject.addProperty("type", def.formatType);
        if (pathParts.length == 1) {
            formatObject.add(pathParts[0], parsed);
        } else {
            JsonObject inner = new JsonObject();
            inner.addProperty("name", "response");
            inner.add(pathParts[1], parsed);
            inner.addProperty("strict", def.enforceStrict);
            formatObject.add(pathParts[0], inner);
        }
        JsonBuilder.setNested(body, def.formatField, formatObject);
    }

    /*



*/
    private static void setAdditionalPropertiesFalse(JsonObject schema) {
        JsonElement type = schema.get("type");
        if (type != null && type.isJsonPrimitive() && "object".equals(type.getAsString())) {
            schema.addProperty("additionalProperties", false);
            JsonElement propsElement = schema.get("properties");
            if (!schema.has("required") && propsElement != null && propsElement.isJsonObject()) {
                JsonArray required = new JsonArray();
                for (String propKey : propsElement.getAsJsonObject().keySet()) {
                    required.add(propKey);
                }
                schema.add("required", required);
            }
            if (propsElement != null && propsElement.isJsonObject()) {
                JsonObject props = propsElement.getAsJsonObject();
                for (Map.Entry<String, JsonElement> entry : props.entrySet()) {
                    if (entry.getValue().isJsonObject()) {
                        setAdditionalPropertiesFalse(entry.getValue().getAsJsonObject());
                    }
                }
            }
        }
        JsonElement items = schema.get("items");
        if (items != null && items.isJsonObject()) {
            setAdditionalPropertiesFalse(items.getAsJsonObject());
        }
    }

    /**/
    private static void removeAdditionalProperties(JsonObject schema) {
        schema.remove("additionalProperties");
        JsonElement propsElement = schema.get("properties");
        if (propsElement != null && propsElement.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : propsElement.getAsJsonObject().entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    removeAdditionalProperties(entry.getValue().getAsJsonObject());
                }
            }
        }
        JsonElement items = schema.get("items");
        if (items != null && items.isJsonObject()) {
            removeAdditionalProperties(items.getAsJsonObject());
        }
    }

    private static JsonPrimitive num(Number value) {
        return value == null ? null : new JsonPrimitive(value);
    }

    private static JsonPrimitive str(String value) {
        return value == null ? null : new JsonPrimitive(value);
    }
}
