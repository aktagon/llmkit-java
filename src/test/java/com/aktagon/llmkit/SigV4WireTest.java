package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

/*






*/
class SigV4WireTest {
    /**/
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 7, 18, 0, 0, 0, 0, ZoneOffset.UTC);

    private static final String ACCESS_KEY = "AKIDEXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY";  // AWS docs example creds #gitleaks:allow
    private static final String SESSION_TOKEN = "IQoJb3JpZ2luX2VjEXAMPLETOKEN";  // AWS docs example creds #gitleaks:allow

    private void assertGolden(String fixture, SigV4.Parts parts) throws IOException {
        JsonObject artifact = Json.object();
        artifact.addProperty("canonicalRequest", parts.canonicalRequest());
        artifact.addProperty("stringToSign", parts.stringToSign());
        artifact.addProperty("authorization", parts.authorization());
        TestPaths.writeSigV4Artifact(fixture, Json.serialize(artifact));

        JsonElement golden =
                Json.parse(TestPaths.read(TestPaths.testdata("wire/sigv4/v1/" + fixture + ".json")));
        for (String key : new String[] {"canonicalRequest", "stringToSign", "authorization"}) {
            assertEquals(
                    Json.stringAt(golden, key),
                    artifact.get(key).getAsString(),
                    fixture + " " + key + " differs from shared golden");
        }
    }

    /*



*/
    @Test
    void sigV4WireChatPost() throws IOException {
        byte[] body = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"text\":\"Hello, Bedrock\"}]}]}"
                .getBytes(StandardCharsets.UTF_8);
        SigV4.Parts parts = SigV4.signParts(
                "POST",
                "https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-3-haiku-20240307-v1:0/converse",
                body, ACCESS_KEY, SECRET_KEY, SESSION_TOKEN,
                "us-east-1", "bedrock", "application/json", NOW);
        assertGolden("sigv4-chat-post", parts);
    }

    /*





*/
    @Test
    void sigV4WirePollGet() throws IOException {
        SigV4.Parts parts = SigV4.signParts(
                "GET",
                "https://bedrock-runtime.us-west-2.amazonaws.com/async-invoke/arn:aws:bedrock:us-west-2:123456789012:async-invoke%2Fabc123xyz",
                new byte[0], ACCESS_KEY, SECRET_KEY, "",
                "us-west-2", "bedrock", "", NOW);
        assertGolden("sigv4-poll-get", parts);
    }
}
