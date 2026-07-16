package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aktagon.llmkit.providers.generated.ProviderName;
import org.junit.jupiter.api.Test;

/**
 * Capability-query parity (ADR-030 / CAP-002): {@code Client.supports} must
 * agree with the generated {@code *Config} lookups the strict pre-flight paths
 * use. Mirrors Swift's {@code SupportsTests}.
 */
class SupportsTest {

    @Test
    void gatedCapabilitiesDispatchGeneratedConfig() {
        Client anthropic = new Client(ProviderName.ANTHROPIC, "key");
        assertTrue(anthropic.supports(Capability.CACHING));
        assertTrue(anthropic.supports(Capability.BATCHING));
        assertTrue(anthropic.supports(Capability.FILE_UPLOAD));
        assertFalse(anthropic.supports(Capability.IMAGE_GENERATION));

        Client cerebras = new Client(ProviderName.CEREBRAS, "key");
        assertFalse(cerebras.supports(Capability.CACHING));
        assertFalse(cerebras.supports(Capability.FILE_UPLOAD));
    }

    @Test
    void ungatedCapabilitiesReturnTrue() {
        Client openai = new Client(ProviderName.OPENAI, "key");
        assertTrue(openai.supports(Capability.CHAT_COMPLETION));
        assertTrue(openai.supports(Capability.REASONING));
        assertTrue(openai.supports(Capability.CATALOGUE));
    }
}
