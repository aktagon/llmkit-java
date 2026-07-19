//

package com.aktagon.llmkit.providers.generated;

import com.aktagon.llmkit.Capability;

/*



*/
public final class Catalogue {
    private Catalogue() {}

    /*



*/
    public static final class CompiledModelDef {
        public final String id;
        public final ProviderName provider;
        public final java.util.List<Capability> capabilities;
        public final String displayName;
        public final String description;
        public final long contextWindow;
        public final long maxOutput;

        CompiledModelDef(
                String id,
                ProviderName provider,
                java.util.List<Capability> capabilities,
                String displayName,
                String description,
                long contextWindow,
                long maxOutput) {
            this.id = id;
            this.provider = provider;
            this.capabilities = capabilities;
            this.displayName = displayName;
            this.description = description;
            this.contextWindow = contextWindow;
            this.maxOutput = maxOutput;
        }
    }

    public static final java.util.List<CompiledModelDef> COMPILED_IN_MODELS = java.util.List.of(
            new CompiledModelDef(
                    "claude-haiku-4-5-20251001",
                    ProviderName.ANTHROPIC,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.TOOL_CALLING),
                    "Claude Haiku 4.5",
                    "",
                    200000,
                    64000),
            new CompiledModelDef(
                    "claude-opus-4-5-20251101",
                    ProviderName.ANTHROPIC,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.TOOL_CALLING),
                    "Claude Opus 4.5",
                    "",
                    200000,
                    64000),
            new CompiledModelDef(
                    "claude-opus-4-6",
                    ProviderName.ANTHROPIC,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.TOOL_CALLING),
                    "Claude Opus 4.6",
                    "",
                    1000000,
                    128000),
            new CompiledModelDef(
                    "claude-opus-4-7",
                    ProviderName.ANTHROPIC,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.TOOL_CALLING),
                    "Claude Opus 4.7",
                    "",
                    1000000,
                    128000),
            new CompiledModelDef(
                    "claude-sonnet-4-5-20250929",
                    ProviderName.ANTHROPIC,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.TOOL_CALLING),
                    "Claude Sonnet 4.5",
                    "",
                    1000000,
                    64000),
            new CompiledModelDef(
                    "claude-sonnet-4-6",
                    ProviderName.ANTHROPIC,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.TOOL_CALLING),
                    "Claude Sonnet 4.6",
                    "",
                    1000000,
                    128000),
            new CompiledModelDef(
                    "gemini-2.5-flash",
                    ProviderName.GOOGLE,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.REASONING, Capability.TOOL_CALLING),
                    "Gemini 2.5 Flash",
                    "Stable version of Gemini 2.5 Flash",
                    1048576,
                    65536),
            new CompiledModelDef(
                    "gemini-2.5-flash-lite",
                    ProviderName.GOOGLE,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.TOOL_CALLING),
                    "Gemini 2.5 Flash-Lite",
                    "Stable version of Gemini 2.5 Flash-Lite",
                    1048576,
                    65536),
            new CompiledModelDef(
                    "gemini-2.5-pro",
                    ProviderName.GOOGLE,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.REASONING, Capability.TOOL_CALLING),
                    "Gemini 2.5 Pro",
                    "Stable release of Gemini 2.5 Pro",
                    1048576,
                    65536),
            new CompiledModelDef(
                    "gemini-3-pro-image-preview",
                    ProviderName.GOOGLE,
                    java.util.List.of(Capability.IMAGE_GENERATION),
                    "Nano Banana Pro",
                    "Gemini 3 Pro Image Preview",
                    131072,
                    32768),
            new CompiledModelDef(
                    "gemini-3-pro-preview",
                    ProviderName.GOOGLE,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.REASONING, Capability.TOOL_CALLING),
                    "Gemini 3 Pro Preview",
                    "Gemini 3 Pro Preview",
                    1048576,
                    65536),
            new CompiledModelDef(
                    "gemini-3.1-flash-image-preview",
                    ProviderName.GOOGLE,
                    java.util.List.of(Capability.IMAGE_GENERATION),
                    "Nano Banana 2",
                    "Gemini 3.1 Flash Image Preview",
                    65536,
                    65536),
            new CompiledModelDef(
                    "gpt-4o",
                    ProviderName.OPENAI,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.TOOL_CALLING),
                    "",
                    "",
                    0,
                    0),
            new CompiledModelDef(
                    "gpt-4o-mini",
                    ProviderName.OPENAI,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.TOOL_CALLING),
                    "",
                    "",
                    0,
                    0),
            new CompiledModelDef(
                    "gpt-5",
                    ProviderName.OPENAI,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.REASONING, Capability.TOOL_CALLING),
                    "",
                    "",
                    0,
                    0),
            new CompiledModelDef(
                    "gpt-image-1",
                    ProviderName.OPENAI,
                    java.util.List.of(Capability.IMAGE_GENERATION),
                    "",
                    "",
                    0,
                    0),
            new CompiledModelDef(
                    "o1",
                    ProviderName.OPENAI,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.REASONING),
                    "",
                    "",
                    0,
                    0),
            new CompiledModelDef(
                    "o3",
                    ProviderName.OPENAI,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.REASONING, Capability.TOOL_CALLING),
                    "",
                    "",
                    0,
                    0),
            new CompiledModelDef(
                    "o4-mini",
                    ProviderName.OPENAI,
                    java.util.List.of(Capability.CHAT_COMPLETION, Capability.REASONING, Capability.TOOL_CALLING),
                    "",
                    "",
                    0,
                    0)
    );

    /*




*/
    public static java.util.List<Capability> ontologyCapabilities(ProviderName provider, String modelId) {
        for (CompiledModelDef m : COMPILED_IN_MODELS) {
            if (m.provider == provider && m.id.equals(modelId)) {
                return m.capabilities;
            }
        }
        return null;
    }

    /*



*/
    public static final class CatalogueConfig {
        public final String endpoint;
        public final String pagination;
        public final String cursorParam;
        public final String parserKind;
        public final String specUrl;
        public final String specFormat;

        CatalogueConfig(
                String endpoint,
                String pagination,
                String cursorParam,
                String parserKind,
                String specUrl,
                String specFormat) {
            this.endpoint = endpoint;
            this.pagination = pagination;
            this.cursorParam = cursorParam;
            this.parserKind = parserKind;
            this.specUrl = specUrl;
            this.specFormat = specFormat;
        }
    }

    private static final CatalogueConfig ANTHROPIC_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "CursorByLastID",
            "after_id",
            "ParseAnthropicModels",
            "https://github.com/anthropics/anthropic-sdk-typescript/blob/main/api.md",
            "OpenAPI3");

    private static final CatalogueConfig CEREBRAS_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "",
            "");

    private static final CatalogueConfig DEEPSEEK_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "",
            "");

    private static final CatalogueConfig FIREWORKS_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "",
            "");

    private static final CatalogueConfig GOOGLE_CATALOGUE = new CatalogueConfig(
            "/v1beta/models",
            "CursorOpaqueToken",
            "pageToken",
            "ParseGoogleModels",
            "https://generativelanguage.googleapis.com/$discovery/rest?version=v1beta",
            "GoogleDiscovery");

    private static final CatalogueConfig GROK_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "",
            "");

    private static final CatalogueConfig GROQ_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "",
            "");

    private static final CatalogueConfig JAN_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "",
            "");

    private static final CatalogueConfig LLAMACPP_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "",
            "");

    private static final CatalogueConfig LMSTUDIO_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "",
            "");

    private static final CatalogueConfig MISTRAL_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "https://raw.githubusercontent.com/mistralai/platform-docs-public/main/openapi.yaml",
            "OpenAPI3");

    private static final CatalogueConfig MOONSHOT_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "",
            "");

    private static final CatalogueConfig OLLAMA_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "https://raw.githubusercontent.com/ollama/ollama/main/docs/openapi.yaml",
            "OpenAPI3");

    private static final CatalogueConfig OPENAI_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "https://github.com/openai/openai-openapi/blob/master/openapi.yaml",
            "OpenAPI3");

    private static final CatalogueConfig OPENROUTER_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "https://openrouter.ai/openapi.json",
            "OpenAPI3");

    private static final CatalogueConfig QWEN_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "",
            "");

    private static final CatalogueConfig TOGETHER_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "https://raw.githubusercontent.com/togethercomputer/openapi/main/openapi.yaml",
            "OpenAPI3");

    private static final CatalogueConfig VLLM_CATALOGUE = new CatalogueConfig(
            "/v1/models",
            "PaginationNone",
            "",
            "ParseOpenAICohortModels",
            "",
            "");

    /**/
    public static CatalogueConfig catalogueConfig(ProviderName provider) {
        switch (provider) {
            case ANTHROPIC: return ANTHROPIC_CATALOGUE;
            case CEREBRAS: return CEREBRAS_CATALOGUE;
            case DEEPSEEK: return DEEPSEEK_CATALOGUE;
            case FIREWORKS: return FIREWORKS_CATALOGUE;
            case GOOGLE: return GOOGLE_CATALOGUE;
            case GROK: return GROK_CATALOGUE;
            case GROQ: return GROQ_CATALOGUE;
            case JAN: return JAN_CATALOGUE;
            case LLAMACPP: return LLAMACPP_CATALOGUE;
            case LMSTUDIO: return LMSTUDIO_CATALOGUE;
            case MISTRAL: return MISTRAL_CATALOGUE;
            case MOONSHOT: return MOONSHOT_CATALOGUE;
            case OLLAMA: return OLLAMA_CATALOGUE;
            case OPENAI: return OPENAI_CATALOGUE;
            case OPENROUTER: return OPENROUTER_CATALOGUE;
            case QWEN: return QWEN_CATALOGUE;
            case TOGETHER: return TOGETHER_CATALOGUE;
            case VLLM: return VLLM_CATALOGUE;
            default: return null;
        }
    }
}
