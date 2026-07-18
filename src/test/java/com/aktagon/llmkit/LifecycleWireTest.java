package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aktagon.llmkit.providers.generated.BatchHandle;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Response;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Cross-SDK LIFECYCLE conformance (ADR-062 slice 1). The INBOUND counterpart
 * to the request-wire suite: given the same provider poll response, every
 * SDK's Job engine must normalize it to the SAME terminal JobStatus. Drives
 * one {@code BatchJob.poll()} round-trip against the scripted transport and
 * drops the normalized {@code {state, hasResult, rawStatus, cause}} projection
 * to {@code target/wire/lifecycle/<fixture>/java.json}, value-equal to the
 * shared golden.
 */
class LifecycleWireTest {

    private BatchJob batchJob(CapturingTransport transport) {
        return new BatchJob(
                new BatchHandle("batch_1", ProviderName.OPENAI, false), "test-key", transport, null);
    }

    private void assertGolden(String fixture, JobStatus<List<Response>> status) throws Exception {
        JsonElement cause = JsonNull.INSTANCE;
        if (status.cause() != null) {
            JsonObject causeObject = new JsonObject();
            causeObject.addProperty("status", status.cause().status());
            causeObject.addProperty("timedOut", status.cause().timedOut());
            cause = causeObject;
        }
        JsonObject projection = new JsonObject();
        projection.addProperty("state", status.state().label());
        projection.addProperty("hasResult", status.result() != null);
        projection.addProperty("rawStatus", status.rawStatus());
        projection.add("cause", cause);

        TestPaths.writeLifecycleArtifact(fixture, projection);
        JsonElement golden =
                Json.parse(TestPaths.read(TestPaths.testdata("wire/lifecycle/v1/" + fixture + ".json")));
        assertEquals(golden, projection, fixture + " differs from shared golden");
    }

    @Test
    void batchSucceeded() throws Exception {
        // Two-hop: the status GET reports completed + output_file_id, then the
        // file-content GET returns one JSONL result line (OpenAI response.body).
        String jsonl = "{\"custom_id\":\"req-0\",\"response\":{\"body\":{\"choices\":[{\"message\":"
                + "{\"role\":\"assistant\",\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":1,"
                + "\"completion_tokens\":1}}}}";
        CapturingTransport transport = new CapturingTransport()
                .enqueue("{\"id\":\"batch_1\",\"status\":\"completed\",\"output_file_id\":\"file-out-1\"}")
                .enqueue(jsonl);
        assertGolden("batch-succeeded", batchJob(transport).poll());
    }

    @Test
    void batchFailed() throws Exception {
        // The status GET reports failed and there is no output_file_id — one
        // round-trip, no result fetch.
        CapturingTransport transport = new CapturingTransport()
                .enqueue("{\"id\":\"batch_1\",\"status\":\"failed\"}");
        assertGolden("batch-failed", batchJob(transport).poll());
    }
}
