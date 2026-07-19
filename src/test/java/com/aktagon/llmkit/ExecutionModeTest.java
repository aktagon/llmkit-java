package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Response;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/*





*/
class ExecutionModeTest {

    //

    @Test
    void agentStopsAtMaxToolIterations() {
        //
        //
        String toolCall = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":"
                + "[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                + "\"arguments\":\"{\\\"city\\\":\\\"Helsinki\\\"}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
        CapturingTransport transport = new CapturingTransport().withResponse(200, toolCall);

        Tool tool = new Tool(
                "get_weather",
                "Get the current weather for a city.",
                Json.parse("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"),
                args -> "sunny, 21C");

        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> new Client(ProviderName.OPENAI, "key", transport)
                        .agent().addTool(tool).maxToolIterations(2)
                        .prompt("What is the weather in Helsinki?"));
        assertEquals("max_tool_iterations", thrown.field());
        assertEquals(
                "validation: max_tool_iterations - max tool iterations (2) reached",
                thrown.getMessage());
    }

    @Test
    void agentRunsToolThenAnswers() {
        //
        String toolCall = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"tool_calls\":"
                + "[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"get_weather\","
                + "\"arguments\":\"{\\\"city\\\":\\\"Helsinki\\\"}\"}}]}}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";
        String answer = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":"
                + "\"It is sunny in Helsinki.\"}}],\"usage\":{\"prompt_tokens\":8,\"completion_tokens\":6}}";
        CapturingTransport transport = new CapturingTransport().enqueue(toolCall).enqueue(answer);

        StringBuilder receivedCity = new StringBuilder();
        Tool tool = new Tool(
                "get_weather",
                "Get the current weather for a city.",
                Json.parse("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}"),
                args -> {
                    receivedCity.append(Json.stringAt(args, "city"));
                    return "sunny, 21C";
                });

        Response response = new Client(ProviderName.OPENAI, "key", transport)
                .agent().addTool(tool).prompt("What is the weather in Helsinki?");

        assertEquals("It is sunny in Helsinki.", response.text());
        //
        assertEquals(18, response.usage().input());
        assertEquals(11, response.usage().output());
        //
        assertEquals("Helsinki", receivedCity.toString());

        //
        JsonElement secondBody = Json.parse(transport.capturedBody);
        JsonElement messagesElement = Json.at(secondBody, "messages");
        JsonArray messages = messagesElement.getAsJsonArray();
        JsonObject toolResult = null;
        for (JsonElement message : messages) {
            if ("tool".equals(Json.stringAt(message, "role"))) {
                toolResult = message.getAsJsonObject();
            }
        }
        assertEquals("sunny, 21C", Json.stringAt(toolResult, "content"));
        assertEquals("call_1", Json.stringAt(toolResult, "tool_call_id"));
    }

    //

    @Test
    void batchSubmitAndAwait() {
        String resultLine = "{\"custom_id\":\"req-0\",\"response\":{\"body\":{\"choices\":"
                + "[{\"message\":{\"role\":\"assistant\",\"content\":\"Helsinki\"}}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}}}";
        CapturingTransport transport = new CapturingTransport()
                .enqueue("{\"id\":\"file-in-1\"}") // multipart upload
                .enqueue("{\"id\":\"batch_1\"}") // create batch
                .enqueue("{\"id\":\"batch_1\",\"status\":\"completed\",\"output_file_id\":\"file-out-1\"}") // poll
                .enqueue(resultLine); // result file content

        BatchJob job = new Client(ProviderName.OPENAI, "key", transport)
                .text().model("gpt-4o-mini").batch("What is the capital of Finland?");
        assertEquals("batch_1", job.handle().id());

        List<Response> responses = job.await();
        assertEquals(1, responses.size());
        assertEquals("Helsinki", responses.get(0).text());
        assertEquals(3, responses.get(0).usage().input());
        assertEquals(1, responses.get(0).usage().output());
    }

    //

    @Test
    void awaitTimesOutWithTypedError() {
        //
        CapturingTransport transport = new CapturingTransport()
                .withResponse(200, "{\"id\":\"batch_1\",\"status\":\"in_progress\"}");
        BatchJob job = new Client(ProviderName.OPENAI, "key", transport)
                .text().model("gpt-4o-mini").batch("ping");
        job.pollIntervalMillis = 1;
        job.pollTimeoutMillis = 5;

        PollTimeoutException timeout = assertThrows(PollTimeoutException.class, job::await);
        assertEquals("openai", timeout.provider());
        assertEquals("batch_1", timeout.id());
    }

    @Test
    void erroredResultLineYieldsPartialResults() {
        //
        //
        //
        String good = "{\"custom_id\":\"req-0\",\"response\":{\"body\":{\"choices\":"
                + "[{\"message\":{\"role\":\"assistant\",\"content\":\"Helsinki\"}}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}}}";
        String errored = "{\"custom_id\":\"req-1\",\"error\":{\"message\":\"model overloaded\"}}";
        CapturingTransport transport = new CapturingTransport()
                .enqueue("{\"id\":\"file-in-1\"}")
                .enqueue("{\"id\":\"batch_1\"}")
                .enqueue("{\"id\":\"batch_1\",\"status\":\"completed\",\"output_file_id\":\"file-out-1\"}")
                .enqueue(good + "\n" + errored);

        List<Response> responses = new Client(ProviderName.OPENAI, "key", transport)
                .text().model("gpt-4o-mini")
                .batch("What is the capital of Finland?", "What is the capital of Sweden?")
                .await();

        assertEquals(1, responses.size());
        assertEquals("Helsinki", responses.get(0).text());
    }

    //

    @Test
    void streamRejectsNonDefaultProtocol() {
        CapturingTransport transport = new CapturingTransport();
        ValidationException e = assertThrows(
                ValidationException.class,
                () -> new Client(ProviderName.OPENAI, "key", transport)
                        .text().protocol("responses").model("gpt-4o-mini").stream("ping", delta -> {}));
        assertEquals("protocol", e.field());
    }

    @Test
    void batchRejectsNonDefaultProtocol() {
        CapturingTransport transport = new CapturingTransport();
        ValidationException e = assertThrows(
                ValidationException.class,
                () -> new Client(ProviderName.OPENAI, "key", transport)
                        .text().protocol("responses").model("gpt-4o-mini").batch("q1"));
        assertEquals("protocol", e.field());
    }
}
