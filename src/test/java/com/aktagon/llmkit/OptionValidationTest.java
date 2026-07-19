package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Response;
import org.junit.jupiter.api.Test;

/*





*/
class OptionValidationTest {

    private static final String ANTHROPIC_REPLY =
            "{\"content\":[{\"type\":\"text\",\"text\":\"Hyvin menee.\"}],"
                    + "\"stop_reason\":\"end_turn\","
                    + "\"usage\":{\"input_tokens\":9,\"output_tokens\":4}}";

    @Test
    void topKOnOpenAiThrowsLoudly() {
        Client client = new Client(ProviderName.OPENAI, "test-key", new CapturingTransport());

        ValidationException err = assertThrows(
                ValidationException.class,
                () -> client.text().model("gpt-4o-mini").topK(40).prompt("Mika on Suomen paakaupunki?"));

        assertEquals("top_k", err.field());
        assertTrue(err.getMessage().contains("not supported by openai"), err.getMessage());
    }

    @Test
    void topKOnAnthropicIsAccepted() {
        CapturingTransport transport = new CapturingTransport().withResponse(200, ANTHROPIC_REPLY);
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", transport);

        Response response =
                client.text().model("claude-sonnet-4-6").topK(40).prompt("Miten menee?");

        assertEquals("Hyvin menee.", response.text());
        assertTrue(transport.capturedBody.contains("\"top_k\":40"), transport.capturedBody);
    }

    @Test
    void thinkingBudgetOnOpenAiThrowsLoudly() {
        Client client = new Client(ProviderName.OPENAI, "test-key", new CapturingTransport());

        ValidationException err = assertThrows(
                ValidationException.class,
                () -> client.text().model("gpt-4o-mini").thinkingBudget(2048).prompt("Selita kvanttilaskenta."));

        assertEquals("thinking_budget", err.field());
        assertTrue(err.getMessage().contains("not supported by openai"), err.getMessage());
    }

    @Test
    void reasoningEffortValueOutsideWhitelistThrowsLoudly() {
        //
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", new CapturingTransport());

        ValidationException err = assertThrows(
                ValidationException.class,
                () -> client.text().model("claude-sonnet-4-6")
                        .reasoningEffort("extreme").prompt("Selita kvanttilaskenta."));

        assertEquals("reasoning_effort", err.field());
        assertTrue(
                err.getMessage().contains("must be one of: low,medium,high,xhigh,max"),
                err.getMessage());
    }
}
