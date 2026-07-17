package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aktagon.llmkit.providers.generated.ProviderName;
import org.junit.jupiter.api.Test;

/**
 * Client.addHeader (ADR-052): a custom header reaches the wire on every send
 * path beside the provider auth header (the BUG-015 authenticated-gateway
 * case), calls accumulate, and an SDK-set header is never clobbered.
 */
class CustomHeaderTest {

    private static final String OPENAI_REPLY =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}],"
                    + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1}}";

    @Test
    void customHeaderRidesBesideAuthOnTextPath() {
        CapturingTransport transport = new CapturingTransport().withResponse(200, OPENAI_REPLY);

        new Client(ProviderName.OPENAI, "test-key", transport)
                .addHeader("cf-aig-authorization", "Bearer gw-token")
                .addHeader("x-trace-id", "abc123")
                .text().model("gpt-4o-mini").prompt("ping");

        assertEquals("Bearer test-key", transport.capturedHeaders.get("Authorization"));
        assertEquals("Bearer gw-token", transport.capturedHeaders.get("cf-aig-authorization"));
        assertEquals("abc123", transport.capturedHeaders.get("x-trace-id"));
    }

    @Test
    void customHeaderNeverClobbersSdkSetHeader() {
        CapturingTransport transport = new CapturingTransport().withResponse(200, OPENAI_REPLY);

        new Client(ProviderName.OPENAI, "test-key", transport)
                .addHeader("authorization", "Bearer spoofed")
                .text().model("gpt-4o-mini").prompt("ping");

        assertEquals("Bearer test-key", transport.capturedHeaders.get("Authorization"));
        assertEquals(null, transport.capturedHeaders.get("authorization"));
    }
}
