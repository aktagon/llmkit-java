package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Catalogue;
import com.aktagon.llmkit.providers.generated.ModelInfo;
import com.aktagon.llmkit.providers.generated.ModelsParsers;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.google.gson.JsonElement;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The single-provider live-catalogue sub-builder (ADR-019). Reached via
 * {@link Models#provider(ProviderName)}. {@link #raw()} opts into populating
 * {@code ModelInfo.raw} per ADR-014. Synchronous HTTP throughout (ADR-068
 * JAVA-004 — unlike Swift/Rust's async catalogue). Port of Swift's {@code
 * ScopedModels} / Rust's {@code ScopedModels} + {@code models.rs}.
 */
public final class ScopedModels {
    private final ProviderName target;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final Capability capFilter;
    private final boolean rawFlag;

    ScopedModels(
            ProviderName target,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            Capability capFilter,
            boolean rawFlag) {
        this.target = target;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.capFilter = capFilter;
        this.rawFlag = rawFlag;
    }

    /** Opt into carrying the parsed provider-native record on each {@code ModelInfo}. */
    public ScopedModels raw() {
        return new ScopedModels(target, apiKey, baseUrlOverride, http, capFilter, true);
    }

    /**
     * Single-provider live HTTP. Paginates per the catalogue config until the
     * parser reports no next cursor, then enriches each record with the
     * ontology-derived capability list. Middleware fires once per call (not
     * once per page) — pre fires before the first request, post fires after
     * the final page (or the first error).
     */
    public List<ModelInfo> list() {
        Catalogue.CatalogueConfig cfg = Catalogue.catalogueConfig(target);
        if (cfg == null) {
            throw CatalogueException.notSupported();
        }
        Providers.Spec pcfg = Providers.config(target);

        Event baseEvent = Event.of(MiddlewareOp.MODELS_LIST, pcfg.slug, "");
        long startNanos = System.nanoTime();
        Middleware.firePre(List.of(), baseEvent);
        try {
            List<ModelsParsers.ParsedModelRecord> records = paginate(pcfg, cfg);
            Middleware.firePost(
                    List.of(), baseEvent.toPost("", null, null, Middleware.elapsedMillis(startNanos)));
            return enrich(records);
        } catch (RuntimeException e) {
            Middleware.firePost(
                    List.of(), baseEvent.toPost("", null, e.getMessage(), Middleware.elapsedMillis(startNanos)));
            throw e;
        }
    }

    /**
     * Single-provider live model fetch. URL shapes pinned in plan 025
     * (Anthropic {@code /v1/models/{id}}, OpenAI {@code /v1/models/{id}},
     * Google {@code /v1beta/models/{id}} — the parser strips {@code models/}
     * from the response, the URL uses the bare ID).
     */
    public ModelInfo get(String id) {
        Catalogue.CatalogueConfig cfg = Catalogue.catalogueConfig(target);
        if (cfg == null) {
            throw CatalogueException.notSupported();
        }
        if ("ParseVertexModels".equals(cfg.parserKind) || "ParseBedrockModels".equals(cfg.parserKind)) {
            throw CatalogueException.notSupported();
        }
        Providers.Spec pcfg = Providers.config(target);

        Event baseEvent = Event.of(MiddlewareOp.MODELS_LIST, pcfg.slug, id);
        long startNanos = System.nanoTime();
        Middleware.firePre(List.of(), baseEvent);
        try {
            byte[] body = fetchCatalogueUrl(pcfg, cfg.endpoint + "/" + id);
            Middleware.firePost(
                    List.of(), baseEvent.toPost("", null, null, Middleware.elapsedMillis(startNanos)));
            ModelsParsers.ParsedModelRecord record = parseSingleRecord(cfg.parserKind, body);
            return enrich(List.of(record)).get(0);
        } catch (RuntimeException e) {
            Middleware.firePost(
                    List.of(), baseEvent.toPost("", null, e.getMessage(), Middleware.elapsedMillis(startNanos)));
            throw e;
        }
    }

    // --- HTTP internals ---

    private List<ModelsParsers.ParsedModelRecord> paginate(Providers.Spec pcfg, Catalogue.CatalogueConfig cfg) {
        String cursor = "";
        List<ModelsParsers.ParsedModelRecord> all = new ArrayList<>();
        while (true) {
            String endpoint = appendCursor(cfg.endpoint, cfg.cursorParam, cursor);
            byte[] body = fetchCatalogueUrl(pcfg, endpoint);
            ModelsParsers.ParsedModelsPage page = dispatchParser(cfg.parserKind, body);
            all.addAll(page.records);
            if (page.nextCursor.isEmpty()) {
                return all;
            }
            cursor = page.nextCursor;
        }
    }

    /**
     * Splices the pagination cursor into the URL using the cursor query-param
     * name carried by the generated {@code CatalogueConfig} (ADR-067 Fix A).
     * An empty cursor or an empty cursorParam (PaginationNone) leaves the URL
     * unchanged.
     */
    private static String appendCursor(String endpoint, String cursorParam, String cursor) {
        if (cursor.isEmpty() || cursorParam.isEmpty()) {
            return endpoint;
        }
        String sep = endpoint.contains("?") ? "&" : "?";
        return endpoint + sep + cursorParam + "=" + urlencode(cursor);
    }

    private static String urlencode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * Fetches one catalogue URL, reusing the same URL/header assembly the
     * chat request path uses ({@link RequestBuilder#buildUrl} /
     * {@link RequestBuilder#buildAuthHeaders}) rather than a duplicate
     * catalogue-specific builder.
     */
    private byte[] fetchCatalogueUrl(Providers.Spec pcfg, String endpoint) {
        String url = RequestBuilder.buildUrl(pcfg, endpoint, apiKey, "", baseUrlOverride);
        Map<String, String> headers = RequestBuilder.buildAuthHeaders(pcfg, apiKey);
        HttpTransport.Result result = http.getText(url, headers);
        if (result.statusCode() >= 200 && result.statusCode() < 300) {
            return result.body();
        }
        if (result.statusCode() == 403 && scopeBodyMatches(result.body())) {
            throw CatalogueException.scope("status " + result.statusCode());
        }
        throw CatalogueException.unavailable("status " + result.statusCode());
    }

    private static boolean scopeBodyMatches(byte[] body) {
        String lower = new String(body, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return lower.contains("scope") || lower.contains("permission");
    }

    private static ModelsParsers.ParsedModelsPage dispatchParser(String kind, byte[] body) {
        try {
            return switch (kind) {
                case "ParseAnthropicModels" -> ModelsParsers.parseAnthropicModelsResponse(body);
                case "ParseGoogleModels" -> ModelsParsers.parseGoogleModelsResponse(body);
                case "ParseOpenAICohortModels" -> ModelsParsers.parseOpenAICohortModelsResponse(body);
                default -> throw CatalogueException.notSupported();
            };
        } catch (ModelsParsers.ModelsParseException e) {
            throw CatalogueException.unavailable("parse " + kind + ": " + e.getMessage());
        }
    }

    /**
     * Adapts the list-parsers to {@link #get(String)}. Each provider returns
     * the single record inline (no envelope) at {@code /v1/models/{id}} —
     * wrap it into a one-record list and reuse the same parser to keep
     * wire-format mapping single-sourced.
     */
    private static ModelsParsers.ParsedModelRecord parseSingleRecord(String kind, byte[] body) {
        String bodyStr = new String(body, StandardCharsets.UTF_8);
        String wrapped = switch (kind) {
            case "ParseAnthropicModels" -> "{\"data\":[" + bodyStr + "]}";
            case "ParseGoogleModels" -> "{\"models\":[" + bodyStr + "]}";
            case "ParseOpenAICohortModels" -> "{\"data\":[" + bodyStr + "]}";
            default -> throw CatalogueException.notSupported();
        };
        ModelsParsers.ParsedModelsPage page = dispatchParser(kind, wrapped.getBytes(StandardCharsets.UTF_8));
        if (page.records.isEmpty()) {
            throw CatalogueException.unavailable("empty single-record response");
        }
        return page.records.get(0);
    }

    private List<ModelInfo> enrich(List<ModelsParsers.ParsedModelRecord> records) {
        List<ModelInfo> out = new ArrayList<>();
        for (ModelsParsers.ParsedModelRecord rec : records) {
            List<Capability> caps = Catalogue.ontologyCapabilities(target, rec.id);
            JsonElement raw = rawFlag ? rec.raw : null;
            out.add(new ModelInfo(
                    rec.id, target, caps != null ? caps : List.of(), rec.displayName, rec.description,
                    rec.contextWindow, rec.maxOutput, rec.created, raw));
        }
        return out;
    }
}
