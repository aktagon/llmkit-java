package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.aktagon.llmkit.providers.generated.Catalogue;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Cross-SDK catalogue request-URL conformance (ADR-067 Fix B / CAT-006) — the
 * Java driver. For a fixed (provider, cursor) from
 * {@code codegen/testdata/wire/catalogue/v1/inputs.json}, the catalogue-list
 * path must assemble a byte-identical {@code {method, url, headers}} across
 * all SDKs.
 *
 * <p>The driver calls the SAME URL/header-assembly seam the paginate loop uses
 * ({@link ScopedModels#buildCatalogueUrl} + {@link ScopedModels#appendCursor}
 * + {@link RequestBuilder#buildAuthHeaders}); the cursorParam comes from the
 * generated {@link Catalogue.CatalogueConfig}, not from inputs.json — so this
 * exercises the generated config. Drops
 * {@code target/wire/catalogue/<case>/java.json} for the cross-SDK comparator
 * ({@code codegen/test_cross_sdk_catalogue.py}) and asserts value-equality
 * in-driver.
 */
class CatalogueWireTest {

    private static ProviderName providerFromSlug(String slug) {
        for (ProviderName p : ProviderName.values()) {
            if (p.slug().equals(slug)) {
                return p;
            }
        }
        throw new IllegalArgumentException("unknown provider slug: " + slug);
    }

    @Test
    void catalogueWire() throws Exception {
        JsonObject inputs = Json.parse(
                        TestPaths.read(TestPaths.testdata("wire/catalogue/v1/inputs.json")))
                .getAsJsonObject();
        String apiKey = inputs.get("apiKey").getAsString();
        JsonObject cases = inputs.getAsJsonObject("cases");

        for (String caseName : cases.keySet()) {
            JsonObject fields = cases.getAsJsonObject(caseName);
            ProviderName provider = providerFromSlug(fields.get("provider").getAsString());
            String cursor = fields.get("cursor").getAsString();

            Client client = new Client(provider, apiKey);
            ScopedModels scoped = client.models().provider(provider);
            Providers.Spec pcfg = Providers.config(provider);
            Catalogue.CatalogueConfig cfg = Catalogue.catalogueConfig(provider);
            assertNotNull(cfg, "no catalogue config for " + provider.slug());

            String url = ScopedModels.appendCursor(
                    scoped.buildCatalogueUrl(pcfg, cfg.endpoint), cfg.cursorParam, cursor);
            Map<String, String> headers = RequestBuilder.buildAuthHeaders(pcfg, apiKey);

            JsonObject headersJson = new JsonObject();
            headers.forEach(headersJson::addProperty);
            JsonObject projection = new JsonObject();
            projection.addProperty("method", "GET");
            projection.addProperty("url", url);
            projection.add("headers", headersJson);

            TestPaths.writeCatalogueArtifact(caseName, projection);

            JsonElement golden = Json.parse(
                    TestPaths.read(TestPaths.testdata("wire/catalogue/v1/" + caseName + ".json")));
            assertEquals(golden, projection, caseName + " request differs from shared golden");
        }
    }
}
