package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.aktagon.llmkit.providers.generated.ImageResponse;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.google.gson.JsonElement;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/*







*/
class ImageTest {
    private CapturingTransport transport;

    private Client client(ProviderName provider, String response) {
        transport = new CapturingTransport().withResponse(200, response);
        return new Client(provider, "key", transport);
    }

    //

    /*



*/
    @Test
    void dataArraySniffsSVG() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>";
        String svgBase64 = Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        String response = "{\"data\":[{\"b64_json\":\"" + svgBase64 + "\"}]}";

        ImageResponse resp = client(ProviderName.RECRAFT, response).image()
                .model("recraftv3_vector")
                .generate("A minimalist line drawing of a sailboat");

        assertEquals(1, resp.images().size());
        assertEquals("image/svg+xml", resp.images().get(0).mimeType());
        assertEquals(svg, new String(resp.images().get(0).bytes(), StandardCharsets.UTF_8));
        //
        assertEquals(0, resp.usage().input());
        assertEquals(0, resp.usage().output());
    }

    //

    @Test
    void vertexBodyWrapsInstancesAndParameters() {
        String pngBase64 =
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGM4YWQEAALyAS2saifrAAAAAElFTkSuQmCC";
        client(ProviderName.VERTEX, "{\"predictions\":[{\"bytesBase64Encoded\":\"" + pngBase64 + "\"}]}")
                .image().model("imagen-3.0-generate-002").aspectRatio("16:9")
                .generate("A lighthouse on a rocky coastline at dusk");

        JsonElement body = Json.parse(transport.capturedBody);
        assertEquals("A lighthouse on a rocky coastline at dusk", Json.stringAt(body, "instances[0].prompt"));
        assertEquals(1L, Json.longAt(body, "parameters.sampleCount"));
        assertEquals("16:9", Json.stringAt(body, "parameters.aspectRatio"));
    }

    //

    @Test
    void requiresModel() {
        ValidationException thrown = assertThrows(
                ValidationException.class, () -> client(ProviderName.GOOGLE, "{}").image().generate("A lighthouse"));
        assertEquals("model", thrown.field());
    }

    @Test
    void rejectsBothEmpty() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.GOOGLE, "{}").image()
                        .model("gemini-3.1-flash-image-preview").generate(""));
        assertEquals("prompt", thrown.field());
    }

    @Test
    void rejectsUnsupportedAspectRatioOnPro() {
        //
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.GOOGLE, "{}").image()
                        .model("gemini-3-pro-image-preview").aspectRatio("1:4").generate("A map"));
        assertEquals("aspect_ratio", thrown.field());
    }

    @Test
    void rejectsQualityOnGoogle() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.GOOGLE, "{}").image()
                        .model("gemini-3.1-flash-image-preview").quality("high").generate("A map"));
        assertEquals("quality", thrown.field());
    }

    @Test
    void rejectsAspectRatioOnRecraft() {
        ValidationException thrown = assertThrows(
                ValidationException.class,
                () -> client(ProviderName.RECRAFT, "{}").image()
                        .model("recraftv3").aspectRatio("1:1").generate("A sailboat"));
        assertEquals("aspect_ratio", thrown.field());
    }
}
