package com.aktagon.llmkit;

/**
 * Catalogue error sentinels (ADR-019). Live provider calls map to one of
 * three kinds:
 *
 * <ul>
 *   <li>{@code not_supported} — provider lacks {@code llm:hasModelsEndpoint}
 *       (no {@code /v1/models} route; nothing to fetch). Vertex and Bedrock
 *       surface this until their dedicated parsers land.
 *   <li>{@code scope} — HTTP 403 whose body mentions scope (OpenAI's {@code
 *       api.model.read} scope is the canonical case).
 *   <li>{@code unavailable} — any other non-2xx response or network failure
 *       during a live HTTP call.
 * </ul>
 *
 * <p>{@link #kind()} is the wire-format discriminant carried in {@code
 * ProviderError.kind} (ADR-019 Amendment 1) — lets consumers branch typed
 * across all six SDKs via a single string compare. Mirrors Go's three
 * {@code ErrModels*} sentinels / Swift's {@code CatalogueError} / Rust's
 * {@code CatalogueError}.
 */
public final class CatalogueException extends LlmKitException {
    private final String kind;

    private CatalogueException(String kind, String message) {
        super(message);
        this.kind = kind;
    }

    static CatalogueException notSupported() {
        return new CatalogueException("not_supported", "llmkit: provider does not expose a models endpoint");
    }

    static CatalogueException unavailable(String detail) {
        return new CatalogueException("unavailable", "llmkit: provider models endpoint unavailable: " + detail);
    }

    static CatalogueException scope(String detail) {
        return new CatalogueException("scope", "llmkit: api key lacks scope for models endpoint: " + detail);
    }

    /** The wire-format discriminant ({@code "not_supported"} | {@code "unavailable"} | {@code "scope"}). */
    public String kind() {
        return kind;
    }
}
