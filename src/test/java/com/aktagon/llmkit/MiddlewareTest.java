package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aktagon.llmkit.providers.generated.ImageResponse;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/*






*/
class MiddlewareTest {
    private static final String CHAT_RESPONSE =
            "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Helsinki\"}}],"
                    + "\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":2}}";

    //

    @Test
    void promptFiresLlmRequestPreAndPost() {
        List<Event> events = new ArrayList<>();
        MiddlewareFn hook = event -> {
            events.add(event);
            return null;
        };
        CapturingTransport transport = new CapturingTransport().withResponse(200, CHAT_RESPONSE);

        Response response = new Client(ProviderName.OPENAI, "key", transport)
                .text().model("gpt-4o-mini").addMiddleware(hook)
                .prompt("What is the capital of Finland?");

        assertEquals("Helsinki", response.text());
        assertEquals(2, events.size());
        //
        assertEquals(MiddlewareOp.LLM_REQUEST, events.get(0).op());
        assertEquals(MiddlewarePhase.PRE, events.get(0).phase());
        assertEquals("openai", events.get(0).provider());
        assertEquals("gpt-4o-mini", events.get(0).model());
        assertNull(events.get(0).usage());
        assertEquals(MiddlewarePhase.POST, events.get(1).phase());
        assertEquals(7, events.get(1).usage().input());
        assertEquals(2, events.get(1).usage().output());
        assertNull(events.get(1).err());
    }

    //

    @Test
    void preVetoAbortsPrompt() {
        List<Event> events = new ArrayList<>();
        RuntimeException blocked = new RuntimeException("policy");
        MiddlewareFn veto = event -> blocked;
        MiddlewareFn observer = event -> {
            events.add(event);
            return null;
        };
        CapturingTransport transport = new CapturingTransport().withResponse(200, CHAT_RESPONSE);

        MiddlewareVetoException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                MiddlewareVetoException.class,
                () -> new Client(ProviderName.OPENAI, "key", transport)
                        .text().model("gpt-4o-mini").addMiddleware(veto).addMiddleware(observer)
                        .prompt("This must not reach the provider."));

        assertEquals(blocked, thrown.getCause());
        //
        //
        assertTrue(events.isEmpty());
        assertNull(transport.capturedBody);
    }

    //

    @Test
    void agentFiresToolCall() {
        String toolCall = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":"
                + "[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                + "\"arguments\":\"{\\\"city\\\":\\\"Helsinki\\\"}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":9,\"completion_tokens\":4}}";
        String answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"It is sunny.\"}}],"
                + "\"usage\":{\"prompt_tokens\":6,\"completion_tokens\":3}}";
        CapturingTransport transport = new CapturingTransport().enqueue(toolCall).enqueue(answer);

        List<Event> events = new ArrayList<>();
        MiddlewareFn hook = event -> {
            events.add(event);
            return null;
        };
        Tool tool = new Tool(
                "get_weather",
                "Get the current weather for a city.",
                Json.parse("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"),
                args -> "sunny, 21C");

        new Client(ProviderName.OPENAI, "key", transport)
                .agent().addTool(tool).addMiddleware(hook).prompt("Weather in Helsinki?");

        //
        Optional<Event> toolPre = events.stream()
                .filter(e -> e.op() == MiddlewareOp.TOOL_CALL && e.phase() == MiddlewarePhase.PRE)
                .findFirst();
        Optional<Event> toolPost = events.stream()
                .filter(e -> e.op() == MiddlewareOp.TOOL_CALL && e.phase() == MiddlewarePhase.POST)
                .findFirst();
        assertTrue(toolPre.isPresent());
        assertEquals("get_weather", toolPre.get().tool());
        assertEquals("Helsinki", toolPre.get().args().get("city").getAsString());
        assertTrue(toolPost.isPresent());
        assertEquals("sunny, 21C", toolPost.get().result());
        assertTrue(events.stream().anyMatch(e -> e.op() == MiddlewareOp.LLM_REQUEST && e.phase() == MiddlewarePhase.POST));
    }

    //

    @Test
    void batchFiresBatchSubmit() {
        List<Event> events = new ArrayList<>();
        MiddlewareFn hook = event -> {
            events.add(event);
            return null;
        };
        CapturingTransport transport = new CapturingTransport().withResponse(200, "{\"id\":\"batch_1\"}");

        new Client(ProviderName.ANTHROPIC, "key", transport)
                .text().model("claude-sonnet-4-6").addMiddleware(hook).batch("q1");

        Optional<Event> pre = events.stream()
                .filter(e -> e.op() == MiddlewareOp.BATCH_SUBMIT && e.phase() == MiddlewarePhase.PRE)
                .findFirst();
        Optional<Event> post = events.stream()
                .filter(e -> e.op() == MiddlewareOp.BATCH_SUBMIT && e.phase() == MiddlewarePhase.POST)
                .findFirst();
        assertTrue(pre.isPresent());
        assertEquals("anthropic", pre.get().provider());
        assertTrue(post.isPresent());
        assertNull(post.get().err());
    }

    //

    @Test
    void resourceCachingFiresCacheCreate() {
        List<Event> events = new ArrayList<>();
        MiddlewareFn hook = event -> {
            events.add(event);
            return null;
        };
        CapturingTransport transport = new CapturingTransport()
                .enqueue("{\"name\":\"cachedContents/abc123\"}") // cache create
                .enqueue("{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"hi\"}]}}],"
                        + "\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":1}}"); // prompt

        new Client(ProviderName.GOOGLE, "key", transport)
                .text().system("a very long stable system prefix used for caching purposes")
                .caching().cacheTtl(1800).addMiddleware(hook).prompt("hi");

        Optional<Event> pre = events.stream()
                .filter(e -> e.op() == MiddlewareOp.CACHE_CREATE && e.phase() == MiddlewarePhase.PRE)
                .findFirst();
        Optional<Event> post = events.stream()
                .filter(e -> e.op() == MiddlewareOp.CACHE_CREATE && e.phase() == MiddlewarePhase.POST)
                .findFirst();
        assertTrue(pre.isPresent());
        assertEquals("google", pre.get().provider());
        assertTrue(post.isPresent());
        assertNull(post.get().err());
        assertTrue(events.stream().anyMatch(e -> e.op() == MiddlewareOp.LLM_REQUEST));
    }

    //

    private static final String IMAGE_RESPONSE =
            "{\"candidates\":[{\"content\":{\"parts\":[{\"inlineData\":{\"mimeType\":\"image/png\","
                    + "\"data\":\"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGM4YWQEAALyAS2s"
                    + "aifrAAAAAElFTkSuQmCC\"}}]}}],"
                    + "\"usageMetadata\":{\"promptTokenCount\":9,\"candidatesTokenCount\":1290}}";

    @Test
    void imageGenerationFiresPreAndPost() {
        List<Event> events = new ArrayList<>();
        MiddlewareFn hook = event -> {
            events.add(event);
            return null;
        };
        CapturingTransport transport = new CapturingTransport().withResponse(200, IMAGE_RESPONSE);

        ImageResponse image = new Client(ProviderName.GOOGLE, "key", transport)
                .image().model("gemini-3.1-flash-image-preview").addMiddleware(hook)
                .generate("A lighthouse on a rocky coastline at dusk");

        assertEquals(1, image.images().size());
        assertEquals(2, events.size());
        assertEquals(MiddlewareOp.IMAGE_GENERATION, events.get(0).op());
        assertEquals(MiddlewarePhase.PRE, events.get(0).phase());
        assertEquals("google", events.get(0).provider());
        assertNull(events.get(0).usage());
        assertEquals(MiddlewarePhase.POST, events.get(1).phase());
        assertEquals(9, events.get(1).usage().input());
        assertEquals(1290, events.get(1).usage().output());
        assertNull(events.get(1).err());
    }

    @Test
    void preVetoAbortsImageGeneration() {
        List<Event> events = new ArrayList<>();
        RuntimeException blocked = new RuntimeException("policy");
        MiddlewareFn veto = event -> blocked;
        MiddlewareFn observer = event -> {
            events.add(event);
            return null;
        };
        CapturingTransport transport = new CapturingTransport().withResponse(200, IMAGE_RESPONSE);

        MiddlewareVetoException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                MiddlewareVetoException.class,
                () -> new Client(ProviderName.GOOGLE, "key", transport)
                        .image().model("gemini-3.1-flash-image-preview").addMiddleware(veto).addMiddleware(observer)
                        .generate("This must not reach the provider."));

        assertEquals(blocked, thrown.getCause());
        //
        //
        assertTrue(events.isEmpty());
        assertNull(transport.capturedBody);
    }
}
