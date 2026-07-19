package com.aktagon.llmkit;

/*


















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

    /**/
    public String kind() {
        return kind;
    }
}
