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
import java.util.Optional;

/*








*/
public final class Models {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final Capability capFilter;
    private final List<MiddlewareFn> middleware;

    private Models(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            Capability capFilter,
            List<MiddlewareFn> middleware) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.capFilter = capFilter;
        this.middleware = middleware;
    }

    static Models root(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            List<MiddlewareFn> middleware) {
        return new Models(provider, apiKey, baseUrlOverride, http, null, middleware);
    }

    /*



*/
    public Models withCapability(Capability c) {
        return new Models(provider, apiKey, baseUrlOverride, http, c, middleware);
    }

    /*




*/
    public ScopedModels provider(ProviderName p) {
        return new ScopedModels(p, apiKey, baseUrlOverride, http, capFilter, false, middleware);
    }

    /*


*/
    public List<ModelInfo> list() {
        List<ModelInfo> out = new ArrayList<>();
        for (Catalogue.CompiledModelDef def : Catalogue.COMPILED_IN_MODELS) {
            out.add(compiledToModelInfo(def));
        }
        return applyCapFilter(out, capFilter);
    }

    /*






*/
    static List<ModelInfo> applyCapFilter(List<ModelInfo> models, Capability capFilter) {
        if (capFilter == null) {
            return models;
        }
        List<ModelInfo> out = new ArrayList<>();
        for (ModelInfo m : models) {
            if (m.capabilities().contains(capFilter)) {
                out.add(m);
            }
        }
        return out;
    }

    /*



*/
    public Optional<ModelInfo> get(String id) {
        for (Catalogue.CompiledModelDef def : Catalogue.COMPILED_IN_MODELS) {
            if (def.id.equals(id)) {
                return Optional.of(compiledToModelInfo(def));
            }
        }
        return Optional.empty();
    }

    /*







*/
    public LiveResult live() {
        List<ProviderInfo> configured = catalogueProvidersList(provider);
        List<ModelInfo> all = new ArrayList<>();
        Map<String, ProviderError> errors = new LinkedHashMap<>();
        for (ProviderInfo info : configured) {
            ScopedModels scoped =
                    new ScopedModels(info.id(), apiKey, baseUrlOverride, http, capFilter, false, middleware);
            try {
                all.addAll(scoped.list());
            } catch (CatalogueException e) {
                errors.put(providerNameSlug(info.id()), new ProviderError(e.kind(), e.getMessage()));
            } catch (TransportException e) {
                //
                //
                //
                CatalogueException mapped = CatalogueException.unavailable(e.getMessage());
                errors.put(providerNameSlug(info.id()), new ProviderError(mapped.kind(), mapped.getMessage()));
            }
        }
        //
        //
        all.sort((a, b) -> {
            int cmp = providerNameSlug(a.provider()).compareTo(providerNameSlug(b.provider()));
            return cmp != 0 ? cmp : a.id().compareTo(b.id());
        });
        return new LiveResult(all, errors);
    }

    private static ModelInfo compiledToModelInfo(Catalogue.CompiledModelDef def) {
        return new ModelInfo(
                def.id, def.provider, def.capabilities, def.displayName, def.description,
                def.contextWindow, def.maxOutput, 0, null);
    }

    static String providerNameSlug(ProviderName provider) {
        return ProviderInfo.providerInfo(provider).slug();
    }

    //
    //
    //
    //
    //
    //

    /*




*/
    static List<ProviderInfo> catalogueProvidersList(ProviderName provider) {
        if (Catalogue.catalogueConfig(provider) == null) {
            return List.of();
        }
        return List.of(ProviderInfo.providerInfo(provider));
    }
}
