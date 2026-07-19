package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/*








*/
class SigV4Test {

    private static final String CHAT_URL =
            "https://bedrock-runtime.us-east-1.amazonaws.com/model/anthropic.claude-3-haiku/invoke";
    private static final String POLL_URL =
            "https://bedrock-runtime.us-east-1.amazonaws.com/async-invoke/arn%3Aaws%3Abedrock%3A123";

    private static String signedHeaders(Map<String, String> headers) {
        String auth = headers.get("Authorization");
        int start = auth.indexOf("SignedHeaders=") + "SignedHeaders=".length();
        return auth.substring(start, auth.indexOf(", Signature="));
    }

    @Test
    void getWithoutBodySignsNoContentType() {
        Map<String, String> headers = SigV4.sign(
                "GET", POLL_URL, new byte[0],
                "AKIAEXAMPLE", "secret", "", "us-east-1", "bedrock", "");
        assertEquals("host;x-amz-content-sha256;x-amz-date", signedHeaders(headers));
        assertFalse(headers.containsKey("Content-Type"));
    }

    @Test
    void postSignsContentType() {
        byte[] body = "{\"messages\":[]}".getBytes(StandardCharsets.UTF_8);
        Map<String, String> headers = SigV4.sign(
                "POST", CHAT_URL, body,
                "AKIAEXAMPLE", "secret", "", "us-east-1", "bedrock", "application/json");
        assertEquals("content-type;host;x-amz-content-sha256;x-amz-date", signedHeaders(headers));
    }

    @Test
    void sessionTokenJoinsSignedSetAndReturnedHeaders() {
        Map<String, String> headers = SigV4.sign(
                "POST", CHAT_URL, new byte[0],
                "AKIAEXAMPLE", "secret", "FwoGZXIvYXdzEXAMPLE", "us-east-1", "bedrock", "application/json");
        assertEquals(
                "content-type;host;x-amz-content-sha256;x-amz-date;x-amz-security-token",
                signedHeaders(headers));
        assertEquals("FwoGZXIvYXdzEXAMPLE", headers.get("X-Amz-Security-Token"));
    }

    @Test
    void hostSignsUrlAuthorityIncludingExplicitPort() {
        Map<String, String> headers = SigV4.sign(
                "POST", "http://localhost:4566/model/anthropic.claude-3-haiku/invoke",
                new byte[0], "AKIAEXAMPLE", "secret", "", "us-east-1", "bedrock", "application/json");
        assertEquals("localhost:4566", headers.get("Host"));
        assertTrue(signedHeaders(headers).contains("host"));
    }
}
