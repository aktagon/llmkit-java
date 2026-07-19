//

package com.aktagon.llmkit.providers.generated;

/*


*/
public record ProviderInfo(
        ProviderName id,
        String slug,
        String envVar,
        String defaultModel,
        String baseUrl,
        /**/
        boolean browserCallable) {

    /**/
    public static ProviderInfo providerInfo(ProviderName provider) {
        switch (provider) {
            case AI21:
                return new ProviderInfo(ProviderName.AI21, "ai21", "AI21_API_KEY", "jamba-1.5-large", "https://api.ai21.com", false);
            case ANTHROPIC:
                return new ProviderInfo(ProviderName.ANTHROPIC, "anthropic", "ANTHROPIC_API_KEY", "claude-sonnet-4-6", "https://api.anthropic.com", false);
            case ASSEMBLYAI:
                return new ProviderInfo(ProviderName.ASSEMBLYAI, "assemblyai", "ASSEMBLYAI_API_KEY", "best", "https://api.assemblyai.com", false);
            case AZURE:
                return new ProviderInfo(ProviderName.AZURE, "azure", "AZURE_OPENAI_API_KEY", "gpt-4o", "https://REPLACE-WITH-YOUR-RESOURCE.openai.azure.com", false);
            case BEDROCK:
                return new ProviderInfo(ProviderName.BEDROCK, "bedrock", "AWS_ACCESS_KEY_ID", "anthropic.claude-sonnet-4-20250514-v1:0", "https://bedrock-runtime.{region}.amazonaws.com", false);
            case CEREBRAS:
                return new ProviderInfo(ProviderName.CEREBRAS, "cerebras", "CEREBRAS_API_KEY", "llama-3.3-70b", "https://api.cerebras.ai", false);
            case COHERE:
                return new ProviderInfo(ProviderName.COHERE, "cohere", "COHERE_API_KEY", "command-r-plus", "https://api.cohere.com/compatibility", false);
            case DEEPSEEK:
                return new ProviderInfo(ProviderName.DEEPSEEK, "deepseek", "DEEPSEEK_API_KEY", "deepseek-chat", "https://api.deepseek.com", false);
            case DOUBAO:
                return new ProviderInfo(ProviderName.DOUBAO, "doubao", "ARK_API_KEY", "doubao-1.5-pro-32k-250115", "https://ark.cn-beijing.volces.com/api/v3", false);
            case ERNIE:
                return new ProviderInfo(ProviderName.ERNIE, "ernie", "QIANFAN_API_KEY", "ernie-4.0-8k", "https://qianfan.baidubce.com/v2", false);
            case FIREWORKS:
                return new ProviderInfo(ProviderName.FIREWORKS, "fireworks", "FIREWORKS_API_KEY", "accounts/fireworks/models/llama-v3p3-70b-instruct", "https://api.fireworks.ai/inference", false);
            case GOOGLE:
                return new ProviderInfo(ProviderName.GOOGLE, "google", "GOOGLE_API_KEY", "gemini-2.5-flash", "https://generativelanguage.googleapis.com", true);
            case GROK:
                return new ProviderInfo(ProviderName.GROK, "grok", "XAI_API_KEY", "grok-3-fast", "https://api.x.ai", false);
            case GROQ:
                return new ProviderInfo(ProviderName.GROQ, "groq", "GROQ_API_KEY", "llama-3.3-70b-versatile", "https://api.groq.com/openai", false);
            case INWORLD:
                return new ProviderInfo(ProviderName.INWORLD, "inworld", "INWORLD_API_KEY", "inworld-tts-2", "https://api.inworld.ai", false);
            case JAN:
                return new ProviderInfo(ProviderName.JAN, "jan", "JAN_API_KEY", "", "http://localhost:1337", false);
            case LLAMACPP:
                return new ProviderInfo(ProviderName.LLAMACPP, "llamacpp", "LLAMACPP_API_KEY", "", "http://localhost:8080", false);
            case LMSTUDIO:
                return new ProviderInfo(ProviderName.LMSTUDIO, "lmstudio", "LM_STUDIO_API_KEY", "", "http://localhost:1234", false);
            case MINIMAX:
                return new ProviderInfo(ProviderName.MINIMAX, "minimax", "MINIMAX_API_KEY", "MiniMax-Text-01", "https://api.minimax.chat", false);
            case MISTRAL:
                return new ProviderInfo(ProviderName.MISTRAL, "mistral", "MISTRAL_API_KEY", "mistral-large-latest", "https://api.mistral.ai", false);
            case MOONSHOT:
                return new ProviderInfo(ProviderName.MOONSHOT, "moonshot", "MOONSHOT_API_KEY", "moonshot-v1-8k", "https://api.moonshot.ai", false);
            case OLLAMA:
                return new ProviderInfo(ProviderName.OLLAMA, "ollama", "OLLAMA_API_KEY", "", "http://localhost:11434", false);
            case OPENAI:
                return new ProviderInfo(ProviderName.OPENAI, "openai", "OPENAI_API_KEY", "gpt-4o-2024-08-06", "https://api.openai.com", false);
            case OPENROUTER:
                return new ProviderInfo(ProviderName.OPENROUTER, "openrouter", "OPENROUTER_API_KEY", "openai/gpt-4o", "https://openrouter.ai/api", false);
            case PERPLEXITY:
                return new ProviderInfo(ProviderName.PERPLEXITY, "perplexity", "PERPLEXITY_API_KEY", "sonar-pro", "https://api.perplexity.ai", false);
            case PIXVERSE:
                return new ProviderInfo(ProviderName.PIXVERSE, "pixverse", "PIXVERSE_API_KEY", "v4.5", "https://app-api.pixverse.ai", false);
            case QWEN:
                return new ProviderInfo(ProviderName.QWEN, "qwen", "DASHSCOPE_API_KEY", "qwen-plus", "https://dashscope-intl.aliyuncs.com/compatible-mode", false);
            case RECRAFT:
                return new ProviderInfo(ProviderName.RECRAFT, "recraft", "RECRAFT_API_TOKEN", "recraftv3", "https://external.api.recraft.ai", false);
            case SAMBANOVA:
                return new ProviderInfo(ProviderName.SAMBANOVA, "sambanova", "SAMBANOVA_API_KEY", "Meta-Llama-3.3-70B-Instruct", "https://api.sambanova.ai", false);
            case TOGETHER:
                return new ProviderInfo(ProviderName.TOGETHER, "together", "TOGETHER_API_KEY", "meta-llama/Llama-3.3-70B-Instruct-Turbo", "https://api.together.xyz", false);
            case VERTEX:
                return new ProviderInfo(ProviderName.VERTEX, "vertex", "VERTEX_BEARER_TOKEN", "imagen-3.0-generate-002", "https://{location}-aiplatform.googleapis.com/v1/projects/{project_id}/locations/{location}/publishers/google/models", false);
            case VIDU:
                return new ProviderInfo(ProviderName.VIDU, "vidu", "VIDU_API_KEY", "viduq3-pro", "https://api.vidu.com", false);
            case VLLM:
                return new ProviderInfo(ProviderName.VLLM, "vllm", "VLLM_API_KEY", "", "http://localhost:8000", false);
            case WORKERSAI:
                return new ProviderInfo(ProviderName.WORKERSAI, "workersai", "CLOUDFLARE_API_TOKEN", "@cf/meta/llama-3.1-8b-instruct", "https://api.cloudflare.com/client/v4/accounts/{account_id}/ai/v1", false);
            case YI:
                return new ProviderInfo(ProviderName.YI, "yi", "YI_API_KEY", "yi-large", "https://api.01.ai", false);
            case ZHIPU:
                return new ProviderInfo(ProviderName.ZHIPU, "zhipu", "ZHIPU_API_KEY", "glm-4-plus", "https://open.bigmodel.cn/api/paas", false);
            default: throw new IllegalStateException("unreachable: " + provider);
        }
    }

    /**/
    public static java.util.List<ProviderInfo> allProviderInfo() {
        java.util.List<ProviderInfo> out = new java.util.ArrayList<>();
        for (ProviderName provider : ProviderName.values()) {
            out.add(providerInfo(provider));
        }
        return java.util.List.copyOf(out);
    }
}
