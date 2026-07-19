//

package com.aktagon.llmkit.providers.generated;

/**/
public enum ProviderName {
    AI21("ai21"),
    ANTHROPIC("anthropic"),
    ASSEMBLYAI("assemblyai"),
    AZURE("azure"),
    BEDROCK("bedrock"),
    CEREBRAS("cerebras"),
    COHERE("cohere"),
    DEEPSEEK("deepseek"),
    DOUBAO("doubao"),
    ERNIE("ernie"),
    FIREWORKS("fireworks"),
    GOOGLE("google"),
    GROK("grok"),
    GROQ("groq"),
    INWORLD("inworld"),
    JAN("jan"),
    LLAMACPP("llamacpp"),
    LMSTUDIO("lmstudio"),
    MINIMAX("minimax"),
    MISTRAL("mistral"),
    MOONSHOT("moonshot"),
    OLLAMA("ollama"),
    OPENAI("openai"),
    OPENROUTER("openrouter"),
    PERPLEXITY("perplexity"),
    PIXVERSE("pixverse"),
    QWEN("qwen"),
    RECRAFT("recraft"),
    SAMBANOVA("sambanova"),
    TOGETHER("together"),
    VERTEX("vertex"),
    VIDU("vidu"),
    VLLM("vllm"),
    WORKERSAI("workersai"),
    YI("yi"),
    ZHIPU("zhipu");

    private final String slug;

    ProviderName(String slug) {
        this.slug = slug;
    }

    /**/
    public String slug() {
        return slug;
    }
}
