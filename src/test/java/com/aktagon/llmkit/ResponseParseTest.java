package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Response;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/*



*/
class ResponseParseTest {

    @Test
    void openAiChatResponseMatchesSharedGolden() throws Exception {
        byte[] body = TestPaths.read(TestPaths.testdata("wire/response/v1/bodies/chat-openai.json"))
                .getBytes(StandardCharsets.UTF_8);
        Providers.Spec config = Providers.config(ProviderName.OPENAI);
        Response response = ResponseParser.parse(config, body);

        //
        //
        JsonObject usage = new JsonObject();
        usage.addProperty("cacheRead", response.usage().cacheRead());
        usage.addProperty("cacheWrite", response.usage().cacheWrite());
        usage.addProperty("cost", response.usage().cost());
        usage.addProperty("input", response.usage().input());
        usage.addProperty("output", response.usage().output());
        usage.addProperty("reasoning", response.usage().reasoning());
        JsonObject projection = new JsonObject();
        projection.addProperty("content", response.text());
        projection.add("error", JsonNull.INSTANCE);
        projection.addProperty("finishReason", response.finishReason());
        projection.add("usage", usage);

        JsonElement golden = Json.parse(
                TestPaths.read(TestPaths.testdata("wire/response/v1/chat-openai.json")));

        assertEquals(golden, projection);
    }
}
