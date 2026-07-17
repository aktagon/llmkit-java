package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Catalogue;
import com.aktagon.llmkit.providers.generated.LiveResult;
import com.aktagon.llmkit.providers.generated.ModelInfo;
import com.aktagon.llmkit.providers.generated.ProviderError;
import com.aktagon.llmkit.providers.generated.ProviderInfo;
import com.aktagon.llmkit.providers.generated.ProviderName;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue builder (ADR-019). Reached via {@code client.models()}.
 * Chain methods clone-on-chain and return a fresh {@code Models}; {@link
 * #list()}/{@link #get(String)} walk the compiled-in slice synchronously,
 * {@link #live()} fans out HTTP across configured providers, {@link
 * #provider(ProviderName)} returns {@link ScopedModels}. Synchronous
 * throughout (ADR-068 JAVA-004 — the Java SDK has no async surface, unlike
 * Swift/Rust's async catalogue). Port of Swift's {@code Models.swift} /
 * Rust's {@code models.rs}.
 */
public final class Models {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final Capability capFilter;

    private Models(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            Capability capFilter) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.capFilter = capFilter;
    }

    static Models root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Models(provider, apiKey, baseUrlOverride, http, null);
    }

    /**
     * Filter the catalogue to models whose ontology-derived capabilities
     * contain {@code c}. Composes with {@link #list()}, {@link #live()}, and
     * {@code provider(p).list()}.
     */
    public Models withCapability(Capability c) {
        return new Models(provider, apiKey, baseUrlOverride, http, c);
    }

    /**
     * Scope the catalogue to a single provider; returns {@link ScopedModels}
     * on which {@code raw()}, {@code list()}, and {@code get(id)} are
     * reachable. Credentials come from the client, so {@code p} supplies only
     * the target provider identity.
     */
    public ScopedModels provider(ProviderName p) {
        return new ScopedModels(p, apiKey, baseUrlOverride, http, capFilter, false);
    }

    /**
     * Returns the compiled-in catalogue, filtered by {@link #withCapability}
     * when set. Synchronous, no IO.
     */
    public List<ModelInfo> list() {
        List<ModelInfo> out = new ArrayList<>();
        for (Catalogue.CompiledModelDef def : Catalogue.COMPILED_IN_MODELS) {
            if (capFilter == null || def.capabilities.contains(capFilter)) {
                out.add(compiledToModelInfo(def));
            }
        }
        return out;
    }

    /** Returns a compiled-in model by id, or null when no entry matches. */
    public ModelInfo get(String id) {
        for (Catalogue.CompiledModelDef def : Catalogue.COMPILED_IN_MODELS) {
            if (def.id.equals(id)) {
                return compiledToModelInfo(def);
            }
        }
        return null;
    }

    /**
     * Walk every provider this client is credentialed for and return an
     * aggregated {@link LiveResult}. Today a client carries one provider, so
     * the result is 0 or 1 underlying calls; the shape leaves room for a
     * future multi-credential client without breaking callers. Errors land in
     * {@code result.errors()} as typed {@link ProviderError} (ADR-019
     * Amendment 1). {@link #withCapability} composes post-fetch.
     */
    public LiveResult live() {
        List<ProviderInfo> configured = catalogueProvidersList(provider);
        List<ModelInfo> all = new ArrayList<>();
        Map<String, ProviderError> errors = new LinkedHashMap<>();
        for (ProviderInfo info : configured) {
            ScopedModels scoped = new ScopedModels(info.id(), apiKey, baseUrlOverride, http, capFilter, false);
            try {
                all.addAll(scoped.list());
            } catch (CatalogueException e) {
                errors.put(providerNameSlug(info.id()), new ProviderError(e.kind(), e.getMessage()));
            }
        }
        List<ModelInfo> filtered = all;
        if (capFilter != null) {
            filtered = new ArrayList<>();
            for (ModelInfo m : all) {
                if (m.capabilities().contains(capFilter)) {
                    filtered.add(m);
                }
            }
        }
        filtered.sort((a, b) -> {
            int cmp = providerNameSlug(a.provider()).compareTo(providerNameSlug(b.provider()));
            return cmp != 0 ? cmp : a.id().compareTo(b.id());
        });
        return new LiveResult(filtered, errors);
    }

    private static ModelInfo compiledToModelInfo(Catalogue.CompiledModelDef def) {
        return new ModelInfo(
                def.id, def.provider, def.capabilities, def.displayName, def.description,
                def.contextWindow, def.maxOutput, 0, null);
    }

    static String providerNameSlug(ProviderName provider) {
        return ProviderInfo.providerInfo(provider).slug();
    }

    // --- Providers-namespace runtime (mirrors go/providers.go's `eligible` /
    // Swift's / Rust's catalogueProvidersList). Folded in here rather than a
    // same-simple-name handwritten sibling of the generated
    // com.aktagon.llmkit.providers.generated.Providers wire-spec registry —
    // the public builder that hosts client.providers() lives in Providers.java
    // and delegates its terminal here.

    /**
     * The single credentialed provider, iff it declares a live models
     * endpoint (ADR-040 PSR-005). Gated by {@link Catalogue#catalogueConfig}
     * so it returns at most the bound provider, never the full registry
     * (BUG-003).
     */
    static List<ProviderInfo> catalogueProvidersList(ProviderName provider) {
        if (Catalogue.catalogueConfig(provider) == null) {
            return List.of();
        }
        return List.of(ProviderInfo.providerInfo(provider));
    }
}
