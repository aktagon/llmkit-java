package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aktagon.llmkit.providers.generated.LiveResult;
import com.aktagon.llmkit.providers.generated.ModelInfo;
import com.aktagon.llmkit.providers.generated.ProviderError;
import com.aktagon.llmkit.providers.generated.ProviderInfo;
import com.aktagon.llmkit.providers.generated.ProviderName;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Catalogue tests (ADR-019). Mirror of Rust rust/tests/{catalogue,
 * catalogue_http}.rs / Swift {@code CatalogueTests}/{@code CatalogueHTTPTests}
 * at reduced count — the request/response wire fixtures + the
 * parity.providers-list lint are the cross-SDK conformance floor; these are
 * the Java-local mock-server + compiled-in-slice behavior checks.
 */
class ModelsTest {

    // --- Compiled-in slice (keyless, no HTTP) ---

    @Test
    void listReturnsCompiledInCatalogue() {
        List<ModelInfo> models = new Client(ProviderName.ANTHROPIC, "test-key").models().list();
        assertFalse(models.isEmpty(), "expected non-empty compiled-in catalogue");
        assertEquals(ProviderName.ANTHROPIC, models.get(0).provider());
    }

    @Test
    void withCapabilityNarrowsToImageGeneration() {
        Models models = new Client(ProviderName.OPENAI, "test-key").models();
        List<ModelInfo> all = models.list();
        List<ModelInfo> imageOnly = models.withCapability(Capability.IMAGE_GENERATION).list();
        assertFalse(imageOnly.isEmpty());
        assertTrue(imageOnly.size() < all.size());
        for (ModelInfo m : imageOnly) {
            assertTrue(m.capabilities().contains(Capability.IMAGE_GENERATION));
        }
    }

    @Test
    void getReturnsKnownModel() {
        ModelInfo got = new Client(ProviderName.ANTHROPIC, "test-key").models().get("claude-opus-4-7");
        assertEquals("claude-opus-4-7", got.id());
    }

    @Test
    void getReturnsNullForUnknownId() {
        assertNull(new Client(ProviderName.ANTHROPIC, "test-key").models().get("nonexistent-model-xyz"));
    }

    // --- Providers-namespace (BUG-003 gate) ---

    @Test
    void providersListReturnsConfiguredProviderWithEndpoint() {
        List<ProviderInfo> got = new Client(ProviderName.ANTHROPIC, "test-key").providers().list();
        assertEquals(1, got.size());
        assertEquals(ProviderName.ANTHROPIC, got.get(0).id());
        assertEquals("anthropic", got.get(0).slug());
    }

    @Test
    void providersListEmptyForEndpointlessProvider() {
        // cohere has no llm:hasModelsEndpoint -> not configured for live catalogue.
        assertTrue(new Client(ProviderName.COHERE, "test-key").providers().list().isEmpty());
    }

    // --- Live HTTP: pagination + parsing per wire shape ---

    @Test
    void scopedListAnthropicCursorPagination() {
        String page1 = "{\"data\":[{\"type\":\"model\",\"id\":\"claude-opus-4-7\","
                + "\"display_name\":\"Claude Opus 4.7\",\"created_at\":\"2026-04-14T00:00:00Z\","
                + "\"max_input_tokens\":1000000,\"max_tokens\":128000},{\"type\":\"model\","
                + "\"id\":\"claude-sonnet-4-6\",\"display_name\":\"Claude Sonnet 4.6\","
                + "\"created_at\":\"2026-04-14T00:00:00Z\",\"max_input_tokens\":1000000,"
                + "\"max_tokens\":128000}],\"has_more\":true,\"last_id\":\"claude-sonnet-4-6\"}";
        String page2 = "{\"data\":[{\"type\":\"model\",\"id\":\"claude-haiku-4-5-20251001\","
                + "\"display_name\":\"Claude Haiku 4.5\",\"created_at\":\"2026-04-14T00:00:00Z\","
                + "\"max_input_tokens\":200000,\"max_tokens\":64000}],\"has_more\":false,"
                + "\"last_id\":\"claude-haiku-4-5-20251001\"}";
        CapturingTransport transport = new CapturingTransport().withResponse(200, "").enqueue(page1).enqueue(page2);
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", transport).baseUrl("https://mock.test");

        List<ModelInfo> models = client.models().provider(ProviderName.ANTHROPIC).list();
        assertEquals(3, models.size());
        assertEquals(2, transport.urls.size());
        assertTrue(transport.urls.get(1).contains("after_id=claude-sonnet-4-6"));
        assertEquals("test-key", transport.capturedHeaders.get("x-api-key"));
        ModelInfo opus = models.stream().filter(m -> m.id().equals("claude-opus-4-7")).findFirst().orElseThrow();
        assertFalse(opus.capabilities().isEmpty(), "ontology-enriched");
    }

    @Test
    void scopedListGoogleOpaqueTokenPagination() {
        String page1 = "{\"models\":[{\"name\":\"models/gemini-2.5-flash\",\"displayName\":\"Gemini 2.5 Flash\","
                + "\"description\":\"Stable\",\"inputTokenLimit\":1048576,\"outputTokenLimit\":65536}],"
                + "\"nextPageToken\":\"opaque-cursor-xyz\"}";
        String page2 = "{\"models\":[{\"name\":\"models/gemini-2.5-pro\",\"displayName\":\"Gemini 2.5 Pro\","
                + "\"description\":\"Stable\",\"inputTokenLimit\":1048576,\"outputTokenLimit\":65536}]}";
        CapturingTransport transport = new CapturingTransport().withResponse(200, "").enqueue(page1).enqueue(page2);
        Client client = new Client(ProviderName.GOOGLE, "test-key", transport).baseUrl("https://mock.test");

        List<ModelInfo> models = client.models().provider(ProviderName.GOOGLE).list();
        assertEquals(2, models.size());
        // Parser strips the models/ prefix from response.name.
        assertEquals("gemini-2.5-flash", models.get(0).id());
        assertEquals(2, transport.urls.size());
        assertTrue(transport.urls.get(0).contains("key=test-key"));
        // Order is contract, not cosmetics: the QueryParamKey splice precedes
        // the cursor param, matching the cross-SDK catalogue-wire golden
        // (?key=...&pageToken=..., CR-003 alignment / HANDOFF-036 Part C).
        assertEquals(
                "https://mock.test/v1beta/models?key=test-key&pageToken=opaque-cursor-xyz",
                transport.urls.get(1));
    }

    @Test
    void scopedListOpenAINonPaginated() {
        String body = "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-5\",\"object\":\"model\","
                + "\"created\":1715367049,\"owned_by\":\"system\"},{\"id\":\"gpt-4o\",\"object\":\"model\","
                + "\"created\":1715367049,\"owned_by\":\"system\"}]}";
        CapturingTransport transport = new CapturingTransport().withResponse(200, body);
        Client client = new Client(ProviderName.OPENAI, "test-key", transport).baseUrl("https://mock.test");

        List<ModelInfo> models = client.models().provider(ProviderName.OPENAI).list();
        assertEquals(2, models.size());
        assertEquals(1, transport.urls.size());
        assertEquals("Bearer test-key", transport.capturedHeaders.get("Authorization"));
    }

    @Test
    void scopedList403ScopeMapsToScopeKind() {
        CapturingTransport transport = new CapturingTransport()
                .withResponse(403, "{\"error\":{\"message\":\"Missing scopes: api.model.read\"}}");
        Client client = new Client(ProviderName.OPENAI, "test-key", transport).baseUrl("https://mock.test");

        CatalogueException err = assertThrows(
                CatalogueException.class, () -> client.models().provider(ProviderName.OPENAI).list());
        assertEquals("scope", err.kind());
    }

    @Test
    void scopedList503MapsToUnavailableKind() {
        CapturingTransport transport = new CapturingTransport().withResponse(503, "{\"error\":\"down\"}");
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", transport).baseUrl("https://mock.test");

        CatalogueException err = assertThrows(
                CatalogueException.class, () -> client.models().provider(ProviderName.ANTHROPIC).list());
        assertEquals("unavailable", err.kind());
    }

    @Test
    void scopedListNotSupportedForEndpointlessProvider() {
        CapturingTransport transport = new CapturingTransport();
        Client client = new Client(ProviderName.COHERE, "test-key", transport);

        CatalogueException err = assertThrows(
                CatalogueException.class, () -> client.models().provider(ProviderName.COHERE).list());
        assertEquals("not_supported", err.kind());
    }

    @Test
    void scopedGetAnthropicSingleRecord() {
        String body = "{\"type\":\"model\",\"id\":\"claude-opus-4-7\",\"display_name\":\"Claude Opus 4.7\","
                + "\"created_at\":\"2026-04-14T00:00:00Z\",\"max_input_tokens\":1000000,\"max_tokens\":128000}";
        CapturingTransport transport = new CapturingTransport().withResponse(200, body);
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", transport).baseUrl("https://mock.test");

        ModelInfo model = client.models().provider(ProviderName.ANTHROPIC).get("claude-opus-4-7");
        assertEquals("claude-opus-4-7", model.id());
        assertFalse(model.capabilities().isEmpty());
        assertTrue(transport.capturedUrl.endsWith("/v1/models/claude-opus-4-7"));
    }

    @Test
    void liveAggregatesPartialSuccessAsTypedProviderError() {
        CapturingTransport transport = new CapturingTransport().withResponse(503, "{}");
        Client client = new Client(ProviderName.OPENAI, "test-key", transport).baseUrl("https://mock.test");

        LiveResult result = client.models().live();
        assertTrue(result.models().isEmpty());
        ProviderError err = result.errors().get("openai");
        assertEquals("unavailable", err.kind());
    }

    @Test
    void liveMapsTransportFailureToUnavailableInsteadOfAborting() {
        // A daemon-down / DNS failure lands in the errors map as
        // "unavailable" (Go parity), never aborts the aggregation.
        HttpTransport downTransport = new HttpTransport() {
            @Override
            public Result postJson(String url, String body, java.util.Map<String, String> headers) {
                throw new TransportException("connection refused", new java.io.IOException("connection refused"));
            }

            @Override
            public Result getText(String url, java.util.Map<String, String> headers) {
                throw new TransportException("connection refused", new java.io.IOException("connection refused"));
            }

            @Override
            public Result postMultipart(
                    String url,
                    java.util.Map<String, String> fields,
                    String fileField,
                    String filename,
                    String fileContentType,
                    byte[] data,
                    java.util.Map<String, String> headers) {
                throw new TransportException("connection refused", new java.io.IOException("connection refused"));
            }

            @Override
            public Result postBytes(String url, byte[] body, java.util.Map<String, String> headers) {
                throw new TransportException("connection refused", new java.io.IOException("connection refused"));
            }

            @Override
            public StreamResult postJsonStreaming(String url, String body, java.util.Map<String, String> headers) {
                throw new TransportException("connection refused", new java.io.IOException("connection refused"));
            }
        };
        Client client = new Client(ProviderName.OPENAI, "test-key", downTransport);

        LiveResult result = client.models().live();

        assertTrue(result.models().isEmpty());
        ProviderError err = result.errors().get("openai");
        assertEquals("unavailable", err.kind());
        assertTrue(err.message().contains("connection refused"), err.message());
    }
    @Test
    void scopedListAppliesCapabilityFilter() {
        // HANDOFF-036 A4: withCapability composes with provider(p).list() -
        // the scoped live list returns only models whose ontology-derived
        // capabilities contain the filter.
        String body = "{\"object\":\"list\",\"data\":["
                + "{\"id\":\"gpt-4o-mini\",\"object\":\"model\",\"created\":1715367049,\"owned_by\":\"system\"},"
                + "{\"id\":\"gpt-image-1\",\"object\":\"model\",\"created\":1715367049,\"owned_by\":\"system\"}]}";
        CapturingTransport transport = new CapturingTransport().withResponse(200, body);
        Client client = new Client(ProviderName.OPENAI, "test-key", transport)
                .baseUrl("https://mock.test");

        List<ModelInfo> unfiltered = client.models().provider(ProviderName.OPENAI).list();
        assertEquals(2, unfiltered.size());

        List<ModelInfo> filtered = client.models()
                .withCapability(Capability.IMAGE_GENERATION)
                .provider(ProviderName.OPENAI)
                .list();
        assertEquals(1, filtered.size());
        assertEquals("gpt-image-1", filtered.get(0).id());
    }

    @Test
    void scopedListFiresClientMiddleware() {
        // HANDOFF-036 A3: client-scoped hooks (the addTelemetry seam) observe
        // catalogue calls - pre fires before the HTTP call, post fires after
        // with a duration.
        String body = "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-5\",\"object\":\"model\","
                + "\"created\":1715367049,\"owned_by\":\"system\"}]}";
        CapturingTransport transport = new CapturingTransport().withResponse(200, body);
        List<Event> events = new ArrayList<>();
        Client client = new Client(ProviderName.OPENAI, "test-key", transport)
                .baseUrl("https://mock.test")
                .addMiddleware(e -> {
                    events.add(e);
                    return null;
                });

        List<ModelInfo> models = client.models().provider(ProviderName.OPENAI).list();
        assertEquals(1, models.size());
        assertEquals(2, events.size());
        assertEquals(MiddlewarePhase.PRE, events.get(0).phase);
        assertEquals(MiddlewareOp.MODELS_LIST, events.get(0).op);
        assertEquals(MiddlewarePhase.POST, events.get(1).phase);
        assertEquals(MiddlewareOp.MODELS_LIST, events.get(1).op);
        assertNotNull(events.get(1).durationMillis);
    }

}
