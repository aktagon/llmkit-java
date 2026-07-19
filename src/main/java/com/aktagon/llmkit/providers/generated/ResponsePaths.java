//

package com.aktagon.llmkit.providers.generated;

/**/
public final class ResponsePaths {
    private ResponsePaths() {}

    /**/
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

    /**/
    public static String usageCostPath(ProviderName provider) {
        switch (provider) {
            case GROK: return "usage.cost_in_usd_ticks";
            case OPENROUTER: return "usage.cost";
            default: return "";
        }
    }

    /**/
    public static double usageCostScale(ProviderName provider) {
        switch (provider) {
            case GROK: return 1e-10;
            default: return 1.0;
        }
    }
}
