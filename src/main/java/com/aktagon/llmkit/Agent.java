package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;
import com.aktagon.llmkit.providers.generated.ToolCall;
import com.aktagon.llmkit.providers.generated.ToolResult;
import com.google.gson.JsonElement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The tool-using agent loop — a port of Swift's {@code Agent} / Rust's
 * {@code agent.rs}. The one stateful builder: it accumulates conversation
 * history across turns. Each {@link #prompt} runs the tool loop, calling
 * registered tools until the model returns a plain-text answer (or
 * {@link #maxToolIterations} is hit). The request body is built through the
 * shared {@code RequestBuilder}, so the agent constructs no wire shape of its
 * own. Middleware fires land with the middleware seam (Java phase 4).
 */
public final class Agent {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private String model;
    private String system;
    private final PromptOptions options = new PromptOptions();
    private final List<Tool> tools = new ArrayList<>();
    private final List<Msg> history = new ArrayList<>();
    private int maxToolIterations = 10;

    Agent(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
    }

    /** Register a tool the model may invoke. Returns this for chaining. */
    public Agent addTool(Tool tool) {
        tools.add(tool);
        return this;
    }

    /** Select the model. Returns this for chaining. */
    public Agent model(String model) {
        this.model = model;
        return this;
    }

    /** Set the system instruction. Returns this for chaining. */
    public Agent system(String system) {
        this.system = system;
        return this;
    }

    /** Cap the number of tool-loop iterations (default 10). Returns this. */
    public Agent maxToolIterations(int value) {
        this.maxToolIterations = value;
        return this;
    }

    /** Append a user turn and run the tool loop to a final text answer. */
    public Response prompt(String message) {
        history.add(new Msg.Text("user", message));
        return runToolLoop();
    }

    private Response runToolLoop() {
        Providers.Spec config = Providers.config(provider);
        String resolvedModel = RequestBuilder.resolveModel(config, model);
        String url = RequestBuilder.buildUrl(config, config.endpoint, apiKey, resolvedModel, baseUrlOverride);
        long totalInput = 0;
        long totalOutput = 0;
        long totalCacheWrite = 0;
        long totalCacheRead = 0;
        long totalReasoning = 0;
        double totalCost = 0.0;

        for (int iteration = 0; iteration < maxToolIterations; iteration++) {
            RequestBuilder.Built built = RequestBuilder.buildBody(
                    config, config.chatWireShape, apiKey, resolvedModel, system, history, tools, options);
            HttpTransport.Result result =
                    RequestBuilder.send(config, url, built.body(), built.headers(), apiKey, http);
            if (result.statusCode() < 200 || result.statusCode() >= 300) {
                throw ResponseParser.parseError(config, result.statusCode(), result.body());
            }
            JsonElement raw = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
            Response parsed = ResponseParser.parse(config, result.body());
            totalInput += parsed.usage().input();
            totalOutput += parsed.usage().output();
            totalCacheWrite += parsed.usage().cacheWrite();
            totalCacheRead += parsed.usage().cacheRead();
            totalReasoning += parsed.usage().reasoning();
            totalCost += parsed.usage().cost();

            List<ToolCall> calls = Transforms.extractToolCalls(raw, config);
            if (calls.isEmpty()) {
                history.add(new Msg.Text("assistant", parsed.text()));
                Usage totalUsage = new Usage(
                        totalInput, totalOutput, totalCacheWrite, totalCacheRead, totalReasoning, totalCost);
                return new Response(
                        parsed.text(), totalUsage, parsed.finishReason(), parsed.finishMessage(), null);
            }

            history.add(new Msg.Calls(calls));
            for (ToolCall call : calls) {
                String content;
                Tool tool = tools.stream().filter(t -> t.name.equals(call.name())).findFirst().orElse(null);
                if (tool != null) {
                    try {
                        content = tool.handler.run(call.input() != null ? call.input() : new com.google.gson.JsonObject());
                    } catch (Exception e) {
                        content = "error: " + e.getMessage();
                    }
                } else {
                    content = "error: unknown tool " + call.name();
                }
                history.add(new Msg.ToolOutput(new ToolResult(call.id(), content)));
            }
        }
        throw new ValidationException(
                "max_tool_iterations", "max tool iterations (" + maxToolIterations + ") reached");
    }
}
