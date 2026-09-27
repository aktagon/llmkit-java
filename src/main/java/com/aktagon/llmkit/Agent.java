package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Message;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.ProviderTurn;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;
import com.aktagon.llmkit.providers.generated.ToolCall;
import com.aktagon.llmkit.providers.generated.ToolResult;
import com.google.gson.JsonElement;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The tool-using agent loop — a port of Swift's {@code Agent} / Rust's
 * {@code agent.rs}. The one stateful builder: it accumulates conversation
 * history across turns. Each {@link #prompt} runs the tool loop, calling
 * registered tools until the model returns a plain-text answer (or
 * {@link #maxToolIterations} is hit). The request body is built through the
 * shared {@code RequestBuilder}, so the agent constructs no wire shape of its
 * own. Fires {@code llmRequest} around each turn and {@code toolCall} around
 * each tool invocation; caching is applied on every turn (BUG-004).
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

    /** Opt into prompt caching (ADR-026) — applied on every turn (BUG-004). Returns this. */
    public Agent caching() {
        options.caching = true;
        return this;
    }

    /** Set the cache TTL in seconds (resource caching only). Returns this. */
    public Agent cacheTtl(int seconds) {
        options.cacheTtl = seconds;
        return this;
    }

    /** Register a middleware hook (observation + pre-phase veto). Returns this. */
    public Agent addMiddleware(MiddlewareFn hook) {
        options.middleware.add(hook);
        return this;
    }

    /** Set the maximum output tokens per turn. Returns this. */
    public Agent maxTokens(int maxTokens) {
        options.maxTokens = maxTokens;
        return this;
    }

    /** Sampling temperature. Returns this. */
    public Agent temperature(double value) {
        options.temperature = value;
        return this;
    }

    /** Nucleus-sampling probability mass. Returns this. */
    public Agent topP(double value) {
        options.topP = value;
        return this;
    }

    /** Top-k sampling cutoff. Returns this. */
    public Agent topK(int value) {
        options.topK = value;
        return this;
    }

    /** Deterministic sampling seed. Returns this. */
    public Agent seed(long value) {
        options.seed = value;
        return this;
    }

    /** Frequency penalty. Returns this. */
    public Agent frequencyPenalty(double value) {
        options.frequencyPenalty = value;
        return this;
    }

    /** Presence penalty. Returns this. */
    public Agent presencePenalty(double value) {
        options.presencePenalty = value;
        return this;
    }

    /** Extended-thinking token budget (Anthropic / Google). Returns this. */
    public Agent thinkingBudget(int value) {
        options.thinkingBudget = value;
        return this;
    }

    /** Reasoning-effort level (provider-validated whitelist). Returns this. */
    public Agent reasoningEffort(String value) {
        options.reasoningEffort = value;
        return this;
    }

    /** Stop sequences. Returns this. */
    public Agent stopSequences(List<String> values) {
        options.stopSequences = new ArrayList<>(values);
        return this;
    }

    /** Google safety settings. Returns this. */
    public Agent safetySettings(List<SafetySetting> values) {
        options.safetySettings = new ArrayList<>(values);
        return this;
    }

    /**
     * Replace the conversation history the next prompt continues from
     * (ADR-020 HIST-007). A message authored here carries no captured provider
     * payload, so its reasoning is not replayed (ADR-085 RSN-005); pass back
     * messages this SDK produced to keep it. Returns this.
     */
    public Agent history(Message... messages) {
        history.clear();
        history.addAll(Msg.fromHistory(List.of(messages)));
        return this;
    }

    /**
     * Attach the provider's response body of the final turn to the returned
     * Response as {@code raw} (ADR-014). Returns this.
     */
    public Agent raw() {
        options.raw = true;
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
        // Seeded from the first turn, not from six zeroes: absorbing
        // addition's identity is a REPORTED zero, so an all-zero seed would
        // claim every dimension was reported even when no turn reported any
        // (ADR-081 AVAIL-005).
        Usage totalUsage = null;

        for (int iteration = 0; iteration < maxToolIterations; iteration++) {
            Event llmEvent = Event.of(MiddlewareOp.LLM_REQUEST, config.slug, resolvedModel);
            long llmStartNanos = System.nanoTime();
            Middleware.firePre(options.middleware, llmEvent);

            JsonElement raw;
            byte[] body;
            Response parsed;
            try {
                // Caching is a shared request-construction step (ADR-026 / BUG-004):
                // applied on every agent turn by construction, like the Text path.
                RequestBuilder.Built built = RequestBuilder.buildBody(
                        config, config.chatWireShape, apiKey, resolvedModel, system, history, tools, options);
                CachingRuntime.apply(built.body(), config, resolvedModel, apiKey, options, http, baseUrlOverride);
                HttpTransport.Result result =
                        RequestBuilder.send(config, url, built.body(), built.headers(), apiKey, http);
                if (result.statusCode() < 200 || result.statusCode() >= 300) {
                    throw ResponseCodec.parseError(config, result.statusCode(), result.body());
                }
                body = result.body();
                raw = Json.parse(new String(body, StandardCharsets.UTF_8));
                parsed = ResponseCodec.decodeResponse(provider, config.chatWireShape, body);
            } catch (RuntimeException e) {
                Middleware.firePost(
                        options.middleware,
                        llmEvent.toPost("", null, e, Middleware.elapsedMillis(llmStartNanos)));
                throw e;
            }
            Middleware.firePost(
                    options.middleware,
                    llmEvent.toPost("", parsed.usage(), null, Middleware.elapsedMillis(llmStartNanos)));

            totalUsage = totalUsage == null ? parsed.usage() : totalUsage.plus(parsed.usage());

            List<ToolCall> calls = Transforms.extractToolCalls(raw, config);
            if (calls.isEmpty()) {
                // The terminal turn is captured too: an agent kept alive for another
                // prompt replays it like any other, and Response carries it so a
                // caller running their own loop can thread the turn forward without
                // parsing raw per provider (ADR-085 § 6).
                history.add(replayable(new Msg.Text("assistant", parsed.text()), parsed.providerTurn()));
                return ResponseCodec.attachRaw(
                        new Response(
                                parsed.text(), totalUsage == null ? Usage.none() : totalUsage, parsed.finishReason(), parsed.finishMessage(), null, parsed.providerTurn()),
                        body,
                        options.raw);
            }

            // Record the assistant turn. The calls are the projection the loop runs
            // tools from; the payload is the same turn as the provider wrote it, and
            // is what the NEXT request sends (ADR-085). Before this, the turn was
            // rebuilt from the calls alone, which silently dropped any prose the
            // model emitted alongside them.
            history.add(replayable(new Msg.Calls(calls), parsed.providerTurn()));
            for (ToolCall call : calls) {
                Map<String, JsonElement> args = Map.of();
                if (call.input() != null && call.input().isJsonObject()) {
                    args = new LinkedHashMap<>();
                    for (Map.Entry<String, JsonElement> entry : call.input().getAsJsonObject().entrySet()) {
                        args.put(entry.getKey(), entry.getValue());
                    }
                }
                Event toolEvent =
                        Event.of(MiddlewareOp.TOOL_CALL, config.slug, resolvedModel).withTool(call.name(), args);
                long toolStartNanos = System.nanoTime();
                Middleware.firePre(options.middleware, toolEvent);

                String content;
                Tool tool = tools.stream().filter(t -> t.name().equals(call.name())).findFirst().orElse(null);
                if (tool != null) {
                    try {
                        content = tool.handler().run(call.input() != null ? call.input() : new com.google.gson.JsonObject());
                    } catch (Exception e) {
                        // Restore the interrupt flag before stringifying — the
                        // loop reports the failure to the model but must not
                        // swallow a thread interruption.
                        for (Throwable t = e; t != null; t = t.getCause()) {
                            if (t instanceof InterruptedException) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                        content = "error: " + e.getMessage();
                    }
                } else {
                    content = "error: unknown tool " + call.name();
                }

                Middleware.firePost(
                        options.middleware,
                        toolEvent.toPost(content, null, null, Middleware.elapsedMillis(toolStartNanos)));

                history.add(new Msg.ToolOutput(new ToolResult(call.id(), content)));
            }
        }
        throw new ValidationException(
                "max_tool_iterations", "max tool iterations (" + maxToolIterations + ") reached");
    }

    /**
     * Wraps a projected turn with the provider's own serialization of it, when the
     * response carried one (ADR-085). Without a payload the projection is the turn,
     * exactly as before this ADR.
     */
    private static Msg replayable(Msg projected, ProviderTurn turn) {
        return turn == null ? projected : new Msg.Turn(turn.wireShape(), turn.wire(), projected);
    }

}
