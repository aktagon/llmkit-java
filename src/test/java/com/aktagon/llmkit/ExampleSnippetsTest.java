package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aktagon.llmkit.providers.generated.AudioData;
import com.aktagon.llmkit.providers.generated.MusicResponse;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Response;
import com.aktagon.llmkit.providers.generated.VideoData;
import com.aktagon.llmkit.providers.generated.VideoResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

/*




*/
class ExampleSnippetsTest {

    @Test
    void streamChain() {
        String sse = String.join(
                "\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"Why did the\"}}]}",
                "data: {\"choices\":[{\"delta\":{\"content\":\" chicken cross the road?\"},"
                        + "\"finish_reason\":\"stop\"}]}",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":8}}",
                "data: [DONE]");
        CapturingTransport transport = new CapturingTransport().withResponse(200, sse);
        Client client = new Client(ProviderName.OPENAI, "test-key", transport);

        //
        Response resp = client.text()
                .system("Be brief")
                .stream("Tell me a joke", System.out::print);

        System.out.println();
        System.out.println("Usage: " + resp.usage().input() + " in / "
                + resp.usage().output() + " out");
        //

        assertEquals("Why did the chicken cross the road?", resp.text());
        assertEquals(12, resp.usage().input());
        assertEquals(8, resp.usage().output());
    }

    @Test
    void batchChain() {
        String resultLines = String.join(
                "\n",
                batchResultLine("req-0", "Bonjour"),
                batchResultLine("req-1", "Hola"),
                batchResultLine("req-2", "Hallo"));
        CapturingTransport transport = new CapturingTransport()
                .enqueue("{\"id\":\"file-in-1\"}") // multipart upload
                .enqueue("{\"id\":\"batch_1\"}") // create batch
                .enqueue("{\"id\":\"batch_1\",\"status\":\"completed\","
                        + "\"output_file_id\":\"file-out-1\"}") // poll: completed
                .enqueue(resultLines); // result file content
        Client client = new Client(ProviderName.OPENAI, "test-key", transport);

        //
        BatchJob job = client.text()
                .system("Be brief")
                .batch(
                        "Translate hello to French",
                        "Translate hello to Spanish",
                        "Translate hello to German");

        List<Response> results = job.await();
        results.forEach(r -> System.out.println(r.text()));
        //

        assertEquals(3, results.size());
        assertEquals("Bonjour", results.get(0).text());
        assertEquals("Hallo", results.get(2).text());
    }

    private static String batchResultLine(String customId, String text) {
        return "{\"custom_id\":\"" + customId + "\",\"response\":{\"body\":{\"choices\":"
                + "[{\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}}}";
    }

    @Test
    void musicChain() {
        //
        String wavBase64 = "UklGRiQAAABXQVZFZm10IBAAAAABAAEAgD4AAAB9AAACABAAZGF0YQAAAAA=";
        CapturingTransport transport = new CapturingTransport().withResponse(
                200,
                "{\"predictions\":[{\"audioContent\":\"" + wavBase64
                        + "\",\"mimeType\":\"audio/wav\"}]}");
        Client client = new Client(ProviderName.VERTEX, "test-token", transport);

        //
        MusicResponse resp = client.music()
                .model("lyria-002")
                .generate("a calm instrumental, warm piano and soft strings");

        AudioData first = resp.audio().get(0);
        System.out.println("got " + first.bytes().length + " bytes (" + first.mimeType() + ")");
        //

        assertEquals("audio/wav", first.mimeType());
        assertEquals(44, first.bytes().length);
    }

    @Test
    void videoChain() {
        CapturingTransport transport = new CapturingTransport()
                .enqueue("{\"request_id\":\"vid_alpine\"}") // submit
                .enqueue("{\"status\":\"done\",\"video\":{"
                        + "\"url\":\"https://xai.example/vid_alpine.mp4\","
                        + "\"duration\":6}}"); // poll: done on the first round-trip
        Client client = new Client(ProviderName.GROK, "test-key", transport);

        //
        VideoJob job = client.video()
                .model("grok-imagine-video")
                .submit("a slow cinematic drone shot flying over snow-capped"
                        + " alpine peaks at golden hour");

        VideoResponse resp = job.await();
        VideoData first = resp.videos().get(0);
        System.out.println("url=" + first.url() + " duration="
                + first.durationSeconds() + "s mime=" + first.mimeType());
        //

        assertEquals("https://xai.example/vid_alpine.mp4", first.url());
        assertEquals(6, first.durationSeconds());
    }
}
