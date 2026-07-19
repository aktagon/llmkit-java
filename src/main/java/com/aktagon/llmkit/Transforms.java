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

/*







*/
final class Transforms {
    private Transforms() {}

    /*


*/
    static boolean hasFileParts(List<Msg> msgs) {
        for (Msg msg : msgs) {
            if (msg instanceof Msg.Media media && !media.files().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    //

    /*


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

    /*




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
            } else if (msg instanceof Msg.Media media) {
                JsonArray content = bedrock
                        ? bedrockContentParts(media.images(), media.text())
                        : flatContentParts(media.images(), media.files(), media.text(), wireShape);
                JsonObject message = new JsonObject();
                message.addProperty("role", mapRole(media.role(), config));
                message.add("content", content);
                messages.add(message);
            }
        }
        return messages;
    }

    /*






*/
    private static JsonArray flatContentParts(
            List<InputImage> images, List<FileRef> files, String text, String wireShape) {
        boolean isAnthropic = "ChatAnthropic".equals(wireShape);
        JsonArray parts = new JsonArray();

        for (FileRef file : files) {
            JsonObject block = new JsonObject();
            if (isAnthropic) {
                block.addProperty("type", "document");
                JsonObject source = new JsonObject();
                source.addProperty("type", "file");
                source.addProperty("file_id", file.id());
                block.add("source", source);
            } else {
                block.addProperty("type", "file");
                JsonObject fileObject = new JsonObject();
                fileObject.addProperty("file_id", file.id());
                block.add("file", fileObject);
            }
            parts.add(block);
        }

        for (InputImage image : images) {
            JsonObject block = new JsonObject();
            if (isAnthropic) {
                block.addProperty("type", "image");
                JsonObject source = new JsonObject();
                if (image.url().startsWith("data:")) {
                    String[] parsed = parseDataUri(image.url());
                    source.addProperty("type", "base64");
                    source.addProperty("media_type", parsed[0]);
                    source.addProperty("data", parsed[1]);
                } else {
                    source.addProperty("type", "url");
                    source.addProperty("url", image.url());
                }
                block.add("source", source);
            } else {
                block.addProperty("type", "image_url");
                JsonObject imageUrl = new JsonObject();
                imageUrl.addProperty("url", image.url());
                imageUrl.addProperty("detail", image.detail().isEmpty() ? "auto" : image.detail());
                block.add("image_url", imageUrl);
            }
            parts.add(block);
        }

        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("type", "text");
        textBlock.addProperty("text", text);
        parts.add(textBlock);
        return parts;
    }

    /*



*/
    private static JsonArray googleParts(List<InputImage> images, List<FileRef> files, String text) {
        JsonArray parts = new JsonArray();
        for (FileRef file : files) {
            JsonObject fileData = new JsonObject();
            fileData.addProperty("file_uri", file.uri());
            fileData.addProperty("mime_type", file.mimeType());
            JsonObject part = new JsonObject();
            part.add("file_data", fileData);
            parts.add(part);
        }
        for (InputImage image : images) {
            if (!image.url().startsWith("data:")) {
                continue;
            }
            String[] parsed = parseDataUri(image.url());
            JsonObject inlineData = new JsonObject();
            inlineData.addProperty("mime_type", parsed[0]);
            inlineData.addProperty("data", parsed[1]);
            JsonObject part = new JsonObject();
            part.add("inline_data", inlineData);
            parts.add(part);
        }
        JsonObject textPart = new JsonObject();
        textPart.addProperty("text", text);
        parts.add(textPart);
        return parts;
    }

    /*



*/
    private static JsonArray bedrockContentParts(List<InputImage> images, String text) {
        JsonArray parts = new JsonArray();
        for (InputImage image : images) {
            String[] parsed = parseDataUri(image.url());
            String mime = parsed[0].isEmpty() ? image.mimeType() : parsed[0];
            JsonObject source = new JsonObject();
            source.addProperty("bytes", parsed[1]);
            JsonObject imageBlock = new JsonObject();
            imageBlock.addProperty("format", bedrockImageFormat(mime));
            imageBlock.add("source", source);
            JsonObject block = new JsonObject();
            block.add("image", imageBlock);
            parts.add(block);
        }
        JsonObject textBlock = new JsonObject();
        textBlock.addProperty("text", text);
        parts.add(textBlock);
        return parts;
    }

    /*


*/
    private static String[] parseDataUri(String url) {
        int comma = url.indexOf(',');
        if (!url.startsWith("data:") || comma < 0) {
            return new String[] {"", url};
        }
        String header = url.substring(5, comma); // after "data:"
        int semicolon = header.indexOf(';');
        String mime = semicolon >= 0 ? header.substring(0, semicolon) : header;
        return new String[] {mime, url.substring(comma + 1)};
    }

    /**/
    private static String bedrockImageFormat(String mime) {
        int slash = mime.lastIndexOf('/');
        return slash >= 0 ? mime.substring(slash + 1) : mime;
    }

    private static JsonArray googleContents(List<Msg> msgs, Providers.Spec config) {
        //
        //
        //
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
            } else if (msg instanceof Msg.Media media) {
                JsonObject content = new JsonObject();
                content.addProperty("role", mapRole(media.role(), config));
                content.add("parts", googleParts(media.images(), media.files(), media.text()));
                contents.add(content);
            }
        }
        return contents;
    }

    //

    /*



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
            function.addProperty("name", tool.name());
            function.addProperty("description", tool.description());
            function.add("parameters", tool.schema());
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
            entry.addProperty("name", tool.name());
            entry.addProperty("description", tool.description());
            entry.add("input_schema", tool.schema());
            defs.add(entry);
        }
        body.add("tools", defs);
    }

    private static void googleFunctionDeclarations(JsonObject body, List<Tool> tools, String paramsField) {
        JsonArray decls = new JsonArray();
        for (Tool tool : tools) {
            JsonObject decl = new JsonObject();
            decl.addProperty("name", tool.name());
            decl.addProperty("description", tool.description());
            decl.add(paramsField, tool.schema());
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
            inputSchema.add("json", tool.schema());
            JsonObject spec = new JsonObject();
            spec.addProperty("name", tool.name());
            spec.addProperty("description", tool.description());
            spec.add("inputSchema", inputSchema);
            JsonObject entry = new JsonObject();
            entry.add("toolSpec", spec);
            defs.add(entry);
        }
        JsonObject toolConfig = new JsonObject();
        toolConfig.add("tools", defs);
        body.add("toolConfig", toolConfig);
    }

    //

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

    //

    /*


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
                        //
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

    //

    private static String argsFormat(ProviderName provider) {
        Request.ToolCallDef def = Request.toolCallConfig(provider);
        return def != null ? def.argsFormat : "json_string";
    }

    /*
*/
    private static JsonElement objectOrEmpty(JsonElement value) {
        return value != null && value.isJsonObject() ? value : new JsonObject();
    }

    /*


*/
    static String mapRole(String role, Providers.Spec config) {
        return config.roleMappings.getOrDefault(role, role);
    }
}
