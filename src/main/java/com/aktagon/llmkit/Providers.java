package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderInfo;
import com.aktagon.llmkit.providers.generated.ProviderName;
import java.util.List;

/**
 * The providers-namespace builder (ADR-019 / ADR-040 PSR-005). Reached via
 * {@code client.providers()}. {@link #list()} returns the providers eligible
 * for {@code Models.live()}: the bound provider IFF it declares a catalogue
 * endpoint (so 0 or 1 element), as secret-free {@link ProviderInfo} — never
 * the full registry (BUG-003). The full static roster of every supported
 * provider is the keyless {@link ProviderInfo#allProviderInfo()}.
 */
public final class Providers {
    private final ProviderName provider;

    Providers(ProviderName provider) {
        this.provider = provider;
    }

    /** The bound provider, iff it declares a live models endpoint (0 or 1 element). */
    public List<ProviderInfo> list() {
        return Models.catalogueProvidersList(provider);
    }
}
