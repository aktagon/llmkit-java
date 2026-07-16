package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Request;
import com.aktagon.llmkit.providers.generated.ToolCall;
import com.aktagon.llmkit.providers.generated.ToolResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Request-body message + tool transforms, selected by the effective
 * {@code chatWireShape} (ADR-047 / ADR-055 discriminator) and the generated
 * {@code ToolCallDef}, NOT by provider name — a port of Swift's
 * {@code Transforms} / Rust's {@code transforms.rs}. Covers the multi-turn
 * message array (text, tool-call, and tool-result turns) for the four chat
 * wire shapes plus tool-definition serialization and response-side tool-call
 * extraction. Media Parts remain deferred to a later phase.
 */
final class Transforms {
    private Transforms() {}

    // --- Message array ---

    /**
     * Append the provider-specific message array to {@code body}, built from
     * the internal message list + an optional system turn.
     */
    static void applyMessageShape(
            JsonObject body, List<Msg> msgs, String system, String wireShape, Providers.Spec config) {
        if ("ChatGoogle".equals(wireShape)) {
            body.add("contents", googleContents(msgs, config));
        } else if ("ChatResponsesOpenAI".equals(wireShape)) {
            body.add("input", flatMessageArray(msgs, system, wireShape, config));
        } else {
            body.add("messages", flatMessageArray(msgs, system, wireShape, config));
        }
    }

    /**
     * The shared flat message array used by both the Chat Completions
     * ({@code messages}) and Responses ({@code input}) envelopes. A leading
     * system turn is emitted only for the MessageInArray placement; Bedrock
     * wraps text content in a {@code [{text}]} block.
     */
    private static JsonArray flatMessageArray(
            List<Msg> msgs, String system, String wireShape, Providers.Spec config) {
        boolean bedrock = "ChatBedrock".equals(wireShape);
        JsonArray messages = new JsonArray();

        if ("MessageInArray".equals(config.systemPlacement) && system != null && !system.isEmpty()) {
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", mapRole("system", config));
            systemMessage.addProperty("content", system);
            messages.add(systemMessage);
        }

        for (Msg msg : msgs) {
            if (msg instanceof Msg.ToolOutput output) {
                messages.add(toolResultMessage(config, output.result()));
            } else if (msg instanceof Msg.Calls calls) {
                messages.add(toolCallMessage(config, calls.calls()));
            } else if (msg instanceof Msg.Text text) {
                JsonObject message = new JsonObject();
                message.addProperty("role", mapRole(text.role(), config));
                if (bedrock) {
                    JsonObject block = new JsonObject();
                    block.addProperty("text", text.text());
                    JsonArray content = new JsonArray();
                    content.add(block);
                    message.add("content", content);
                } else {
                    message.addProperty("content", text.text());
                }
                messages.add(message);
            }
        }
        return messages;
    }

    private static JsonArray googleContents(List<Msg> msgs, Providers.Spec config) {
        // Google identifies a tool result by function NAME, but ToolResult
        // carries only tool_use_id. Recover id->name from the preceding call
        // turns (which always precede their result in a valid history).
        Map<String, String> idToName = new HashMap<>();
        JsonArray contents = new JsonArray();
        for (Msg msg : msgs) {
            if (msg instanceof Msg.ToolOutput output) {
                ToolResult result = output.result();
                String name = idToName.get(result.toolUseId());
                if (name != null) {
                    result = new ToolResult(name, result.content());
                }
                contents.add(toolResultMessage(config, result));
            } else if (msg instanceof Msg.Calls calls) {
                for (ToolCall call : calls.calls()) {
                    idToName.put(call.id(), call.name());
                }
                contents.add(toolCallMessage(config, calls.calls()));
            } else if (msg instanceof Msg.Text text) {
                JsonObject part = new JsonObject();
                part.addProperty("text", text.text());
                JsonArray parts = new JsonArray();
                parts.add(part);
                JsonObject content = new JsonObject();
                content.addProperty("role", mapRole(text.role(), config));
                content.add("parts", parts);
                contents.add(content);
            }
        }
        return contents;
    }

    // --- Tool definitions ---

    /**
     * Serialize the tool definitions into the provider-specific wire field,
     * selected by {@code chatWireShape} + the generated
     * {@code ToolCallDef.argsFormat}.
     */
    static void applyToolDefs(JsonObject body, Providers.Spec config, List<Tool> tools) {
        if (tools.isEmpty()) {
            return;
        }
        if ("ChatBedrock".equals(config.chatWireShape)) {
            bedrockToolDefs(body, tools);
        } else if ("ChatGoogle".equals(config.chatWireShape)) {
            Request.ToolCallDef def = Request.toolCallConfig(config.name);
            String field = def != null && !def.paramsWireField.isEmpty() ? def.paramsWireField : "parameters";
            googleFunctionDeclarations(body, tools, field);
        } else if (argsFormat(config.name).equals("map")) {
            anthropicTools(body, tools);
        } else {
            openaiFunctions(body, tools);
        }
    }

    private static void openaiFunctions(JsonObject body, List<Tool> tools) {
        JsonArray defs = new JsonArray();
        for (Tool tool : tools) {
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.name);
            function.addProperty("description", tool.description);
            function.add("parameters", tool.schema);
            JsonObject entry = new JsonObject();
            entry.addProperty("type", "function");
            entry.add("function", function);
            defs.add(entry);
        }
        body.add("tools", defs);
    }

    private static void anthropicTools(JsonObject body, List<Tool> tools) {
        JsonArray defs = new JsonArray();
        for (Tool tool : tools) {
            JsonObject entry = new JsonObject();
            entry.addProperty("name", tool.name);
            entry.addProperty("description", tool.description);
            entry.add("input_schema", tool.schema);
            defs.add(entry);
        }
        body.add("tools", defs);
    }

    private static void googleFunctionDeclarations(JsonObject body, List<Tool> tools, String paramsField) {
        JsonArray decls = new JsonArray();
        for (Tool tool : tools) {
            JsonObject decl = new JsonObject();
            decl.addProperty("name", tool.name);
            decl.addProperty("description", tool.description);
            decl.add(paramsField, tool.schema);
            decls.add(decl);
        }
        JsonObject wrapper = new JsonObject();
        wrapper.add("functionDeclarations", decls);
        JsonArray toolsArray = new JsonArray();
        toolsArray.add(wrapper);
        body.add("tools", toolsArray);
    }

    private static void bedrockToolDefs(JsonObject body, List<Tool> tools) {
        JsonArray defs = new JsonArray();
        for (Tool tool : tools) {
            JsonObject inputSchema = new JsonObject();
            inputSchema.add("json", tool.schema);
            JsonObject spec = new JsonObject();
            spec.addProperty("name", tool.name);
            spec.addProperty("description", tool.description);
            spec.add("inputSchema", inputSchema);
            JsonObject entry = new JsonObject();
            entry.add("toolSpec", spec);
            defs.add(entry);
        }
        JsonObject toolConfig = new JsonObject();
        toolConfig.add("tools", defs);
        body.add("toolConfig", toolConfig);
    }

    // --- Tool-call / tool-result turn messages ---

    private static JsonElement toolCallInput(ToolCall call) {
        return call.input() != null ? call.input() : new JsonObject();
    }

    static JsonObject toolCallMessage(Providers.Spec config, List<ToolCall> calls) {
        if ("ChatBedrock".equals(config.chatWireShape)) {
            JsonArray content = new JsonArray();
            for (ToolCall call : calls) {
                JsonObject toolUse = new JsonObject();
                toolUse.addProperty("toolUseId", call.id());
                toolUse.addProperty("name", call.name());
                toolUse.add("input", toolCallInput(call));
                JsonObject block = new JsonObject();
                block.add("toolUse", toolUse);
                content.add(block);
            }
            JsonObject message = new JsonObject();
            message.addProperty("role", mapRole("assistant", config));
            message.add("content", content);
            return message;
        }
        if ("ChatGoogle".equals(config.chatWireShape)) {
            JsonArray parts = new JsonArray();
            for (ToolCall call : calls) {
                JsonObject functionCall = new JsonObject();
                functionCall.addProperty("name", call.name());
                functionCall.add("args", toolCallInput(call));
                JsonObject part = new JsonObject();
                part.add("functionCall", functionCall);
                parts.add(part);
            }
            JsonObject message = new JsonObject();
            message.addProperty("role", mapRole("assistant", config));
            message.add("parts", parts);
            return message;
        }
        if (argsFormat(config.name).equals("map")) {
            JsonArray content = new JsonArray();
            for (ToolCall call : calls) {
                JsonObject block = new JsonObject();
                block.addProperty("type", "tool_use");
                block.addProperty("id", call.id());
                block.addProperty("name", call.name());
                block.add("input", toolCallInput(call));
                content.add(block);
            }
            JsonObject message = new JsonObject();
            message.addProperty("role", mapRole("assistant", config));
            message.add("content", content);
            return message;
        }
        JsonArray toolCalls = new JsonArray();
        for (ToolCall call : calls) {
            JsonObject function = new JsonObject();
            function.addProperty("name", call.name());
            function.addProperty("arguments", Json.serialize(toolCallInput(call)));
            JsonObject entry = new JsonObject();
            entry.addProperty("id", call.id());
            entry.addProperty("type", "function");
            entry.add("function", function);
            toolCalls.add(entry);
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", mapRole("assistant", config));
        message.add("tool_calls", toolCalls);
        return message;
    }

    static JsonObject toolResultMessage(Providers.Spec config, ToolResult result) {
        if ("ChatBedrock".equals(config.chatWireShape)) {
            JsonObject textBlock = new JsonObject();
            textBlock.addProperty("text", result.content());
            JsonArray innerContent = new JsonArray();
            innerContent.add(textBlock);
            JsonObject toolResult = new JsonObject();
            toolResult.addProperty("toolUseId", result.toolUseId());
            toolResult.add("content", innerContent);
            JsonObject block = new JsonObject();
            block.add("toolResult", toolResult);
            JsonArray content = new JsonArray();
            content.add(block);
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.add("content", content);
            return message;
        }
        if ("ChatGoogle".equals(config.chatWireShape)) {
            JsonObject responseValue = new JsonObject();
            responseValue.addProperty("result", result.content());
            JsonObject functionResponse = new JsonObject();
            functionResponse.addProperty("name", result.toolUseId());
            functionResponse.add("response", responseValue);
            JsonObject part = new JsonObject();
            part.add("functionResponse", functionResponse);
            JsonArray parts = new JsonArray();
            parts.add(part);
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.add("parts", parts);
            return message;
        }
        Request.ToolCallDef def = Request.toolCallConfig(config.name);
        if (def != null && "user".equals(def.resultRole) && "map".equals(def.argsFormat)) {
            JsonObject block = new JsonObject();
            block.addProperty("type", "tool_result");
            block.addProperty("tool_use_id", result.toolUseId());
            block.addProperty("content", result.content());
            JsonArray content = new JsonArray();
            content.add(block);
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.add("content", content);
            return message;
        }
        JsonObject message = new JsonObject();
        message.addProperty("role", "tool");
        message.addProperty("content", result.content());
        message.addProperty("tool_call_id", result.toolUseId());
        return message;
    }

    // --- Tool-call extraction (response side) ---

    /**
     * Extract the tool calls the model issued in the raw response, selected by
     * {@code chatWireShape} + {@code ToolCallDef.argsFormat}.
     */
    static List<ToolCall> extractToolCalls(JsonElement raw, Providers.Spec config) {
        if ("ChatBedrock".equals(config.chatWireShape)) {
            return extractBedrockToolCalls(raw);
        }
        if ("ChatGoogle".equals(config.chatWireShape)) {
            return extractGoogleToolCalls(raw);
        }
        if (argsFormat(config.name).equals("map")) {
            return extractAnthropicToolCalls(raw);
        }
        return extractOpenAiToolCalls(raw, config);
    }

    private static List<ToolCall> extractOpenAiToolCalls(JsonElement raw, Providers.Spec config) {
        List<ToolCall> result = new ArrayList<>();
        JsonElement callsElement = Json.at(raw, "choices[0].message.tool_calls");
        if (callsElement == null || !callsElement.isJsonArray()) {
            return result;
        }
        String argsFormat = argsFormat(config.name);
        for (JsonElement callElement : callsElement.getAsJsonArray()) {
            if (!callElement.isJsonObject()) {
                continue;
            }
            JsonObject call = callElement.getAsJsonObject();
            JsonElement function = call.get("function");
            if (function == null || !function.isJsonObject()) {
                continue;
            }
            String name = Json.stringAt(function, "name");
            if (name.isEmpty()) {
                continue;
            }
            JsonElement input = new JsonObject();
            if ("json_string".equals(argsFormat)) {
                String arguments = Json.stringAt(function, "arguments");
                if (!arguments.isEmpty()) {
                    try {
                        JsonElement parsed = Json.parse(arguments);
                        if (parsed.isJsonObject()) {
                            input = parsed;
                        }
                    } catch (DecodingException e) {
                        // Malformed arguments degrade to an empty input object.
                    }
                }
            } else {
                JsonElement arguments = function.getAsJsonObject().get("arguments");
                if (arguments != null && arguments.isJsonObject()) {
                    input = arguments;
                }
            }
            result.add(new ToolCall(Json.stringAt(call, "id"), name, input));
        }
        return result;
    }

    private static List<ToolCall> extractAnthropicToolCalls(JsonElement raw) {
        List<ToolCall> result = new ArrayList<>();
        JsonElement blocks = Json.at(raw, "content");
        if (blocks == null || !blocks.isJsonArray()) {
            return result;
        }
        for (JsonElement blockElement : blocks.getAsJsonArray()) {
            if (!blockElement.isJsonObject()) {
                continue;
            }
            JsonObject block = blockElement.getAsJsonObject();
            if (!"tool_use".equals(Json.stringAt(block, "type"))) {
                continue;
            }
            result.add(new ToolCall(
                    Json.stringAt(block, "id"), Json.stringAt(block, "name"), objectOrEmpty(block.get("input"))));
        }
        return result;
    }

    private static List<ToolCall> extractGoogleToolCalls(JsonElement raw) {
        List<ToolCall> result = new ArrayList<>();
        JsonElement parts = Json.at(raw, "candidates[0].content.parts");
        if (parts == null || !parts.isJsonArray()) {
            return result;
        }
        for (JsonElement partElement : parts.getAsJsonArray()) {
            if (!partElement.isJsonObject()) {
                continue;
            }
            JsonElement fc = partElement.getAsJsonObject().get("functionCall");
            if (fc == null || !fc.isJsonObject()) {
                continue;
            }
            String name = Json.stringAt(fc, "name");
            result.add(new ToolCall(name, name, objectOrEmpty(fc.getAsJsonObject().get("args"))));
        }
        return result;
    }

    private static List<ToolCall> extractBedrockToolCalls(JsonElement raw) {
        List<ToolCall> result = new ArrayList<>();
        JsonElement blocks = Json.at(raw, "output.message.content");
        if (blocks == null || !blocks.isJsonArray()) {
            return result;
        }
        for (JsonElement blockElement : blocks.getAsJsonArray()) {
            if (!blockElement.isJsonObject()) {
                continue;
            }
            JsonElement toolUse = blockElement.getAsJsonObject().get("toolUse");
            if (toolUse == null || !toolUse.isJsonObject()) {
                continue;
            }
            result.add(new ToolCall(
                    Json.stringAt(toolUse, "toolUseId"),
                    Json.stringAt(toolUse, "name"),
                    objectOrEmpty(toolUse.getAsJsonObject().get("input"))));
        }
        return result;
    }

    // --- Helpers ---

    private static String argsFormat(ProviderName provider) {
        Request.ToolCallDef def = Request.toolCallConfig(provider);
        return def != null ? def.argsFormat : "json_string";
    }

    /** The value if it is an object, else an empty object — for a tool-call
     * input that may be absent or non-object. */
    private static JsonElement objectOrEmpty(JsonElement value) {
        return value != null && value.isJsonObject() ? value : new JsonObject();
    }

    /**
     * Translate a canonical role to the provider's wire role (identity when the
     * provider declares no mapping).
     */
    static String mapRole(String role, Providers.Spec config) {
        return config.roleMappings.getOrDefault(role, role);
    }
}
