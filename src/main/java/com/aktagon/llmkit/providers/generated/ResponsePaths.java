// Code generated — DO NOT EDIT.

package com.aktagon.llmkit.providers.generated;

/** Per-provider response fact tables (ADR-027 cost path + scale). */
public final class ResponsePaths {
    private ResponsePaths() {}

    /** Dotted-from-root input/output token-count paths. */
    public static final class UsagePaths {
        public final String input;
        public final String output;

        UsagePaths(String input, String output) {
            this.input = input;
            this.output = output;
        }
    }

    public static String responseTextPath(ProviderName provider) {
        switch (provider) {
            case AI21: return "choices[0].message.content";
            case ANTHROPIC: return "content[0].text";
            case ASSEMBLYAI: return "";
            case AZURE: return "choices[0].message.content";
            case BEDROCK: return "output.message.content[0].text";
            case CEREBRAS: return "choices[0].message.content";
            case COHERE: return "choices[0].message.content";
            case DEEPSEEK: return "choices[0].message.content";
            case DOUBAO: return "choices[0].message.content";
            case ERNIE: return "choices[0].message.content";
            case FIREWORKS: return "choices[0].message.content";
            case GOOGLE: return "candidates[0].content.parts[0].text";
            case GROK: return "choices[0].message.content";
            case GROQ: return "choices[0].message.content";
            case INWORLD: return "";
            case JAN: return "choices[0].message.content";
            case LLAMACPP: return "choices[0].message.content";
            case LMSTUDIO: return "choices[0].message.content";
            case MINIMAX: return "choices[0].message.content";
            case MISTRAL: return "choices[0].message.content";
            case MOONSHOT: return "choices[0].message.content";
            case OLLAMA: return "choices[0].message.content";
            case OPENAI: return "choices[0].message.content";
            case OPENROUTER: return "choices[0].message.content";
            case PERPLEXITY: return "choices[0].message.content";
            case PIXVERSE: return "";
            case QWEN: return "choices[0].message.content";
            case RECRAFT: return "";
            case SAMBANOVA: return "choices[0].message.content";
            case TOGETHER: return "choices[0].message.content";
            case VERTEX: return "";
            case VIDU: return "";
            case VLLM: return "choices[0].message.content";
            case WORKERSAI: return "choices[0].message.content";
            case YI: return "choices[0].message.content";
            case ZHIPU: return "choices[0].message.content";
            default: throw new IllegalStateException("unreachable: " + provider);
        }
    }

    /**
     * Where the assistant's text sits in a block-ARRAY response, located by
     * discriminator rather than array position: a leading thinking block or
     * non-text part shifts text out from under a fixed path (BUG-053).
     *
     * <p>markerPath empty: every element is a text block. markerPath set with
     * markerValue empty: element is text if the key is PRESENT. Both set:
     * element is text if the key EQUALS the value.
     *
     * <p>markerValue is also a WRITE instruction: encodeResponse stamps it onto
     * the block it writes, so an emitted body reads back through this table.
     */
    public static final class ResponseTextConfig {
        public final String blocksPath;
        public final String markerPath;
        public final String markerValue;
        public final String valuePath;

        ResponseTextConfig(String blocksPath, String markerPath, String markerValue, String valuePath) {
            this.blocksPath = blocksPath;
            this.markerPath = markerPath;
            this.markerValue = markerValue;
            this.valuePath = valuePath;
        }
    }

    /**
     * Text-block selector for a chat wire shape, or null when the shape carries
     * text as a plain scalar — null SELECTS the responseTextPath reader above,
     * it does not mean the shape has no text.
     */
    public static ResponseTextConfig responseTextConfig(String chatWireShape) {
        switch (chatWireShape) {
            case "ChatAnthropic":
                return new ResponseTextConfig("content", "type", "text", "text");
            case "ChatBedrock":
                return new ResponseTextConfig("output.message.content", "text", "", "text");
            case "ChatGoogle":
                return new ResponseTextConfig("candidates[0].content.parts", "text", "", "text");
            default: return null;
        }
    }

    public static UsagePaths usagePaths(ProviderName provider) {
        switch (provider) {
            case AI21: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case ANTHROPIC: return new UsagePaths("usage.input_tokens", "usage.output_tokens");
            case ASSEMBLYAI: return new UsagePaths("", "");
            case AZURE: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case BEDROCK: return new UsagePaths("usage.inputTokens", "usage.outputTokens");
            case CEREBRAS: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case COHERE: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case DEEPSEEK: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case DOUBAO: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case ERNIE: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case FIREWORKS: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case GOOGLE: return new UsagePaths("usageMetadata.promptTokenCount", "usageMetadata.candidatesTokenCount");
            case GROK: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case GROQ: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case INWORLD: return new UsagePaths("", "");
            case JAN: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case LLAMACPP: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case LMSTUDIO: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case MINIMAX: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case MISTRAL: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case MOONSHOT: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case OLLAMA: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case OPENAI: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case OPENROUTER: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case PERPLEXITY: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case PIXVERSE: return new UsagePaths("", "");
            case QWEN: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case RECRAFT: return new UsagePaths("", "");
            case SAMBANOVA: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case TOGETHER: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case VERTEX: return new UsagePaths("", "");
            case VIDU: return new UsagePaths("", "");
            case VLLM: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case WORKERSAI: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case YI: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            case ZHIPU: return new UsagePaths("usage.prompt_tokens", "usage.completion_tokens");
            default: throw new IllegalStateException("unreachable: " + provider);
        }
    }

    /** ADR-027: provider-reported cost path; empty when unreported. */
    public static String usageCostPath(ProviderName provider) {
        switch (provider) {
            case GROK: return "usage.cost_in_usd_ticks";
            case OPENROUTER: return "usage.cost";
            default: return "";
        }
    }

    /** ADR-027: multiplier converting the reported cost to USD (xAI ticks -> 1e-10). */
    public static double usageCostScale(ProviderName provider) {
        switch (provider) {
            case GROK: return 1e-10;
            default: return 1.0;
        }
    }
}
