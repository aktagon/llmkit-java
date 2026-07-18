package com.aktagon.llmkit;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * AWS Signature Version 4 signing for Bedrock (the SigV4 auth scheme). A port
 * of {@code go/sigv4.go} / Swift's {@code SigV4}, using {@code javax.crypto}
 * HMAC-SHA256 — stdlib, no dependency (ADR-068 JAVA-003). Returns the headers
 * to add to the outbound request; the timestamp is the only non-deterministic
 * signing input, so the CR-002 wire driver ({@code SigV4WireTest}) injects a
 * frozen clock via {@link #signParts} and asserts the canonical request /
 * string-to-sign / Authorization against the shared golden at
 * {@code codegen/testdata/wire/sigv4/v1/}.
 */
final class SigV4 {
    private static final DateTimeFormatter DATESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'");

    private SigV4() {}

    /**
     * The intermediate signing artifacts. Production callers use only
     * {@code headers}; the wire driver asserts the other three against the
     * shared golden.
     */
    record Parts(
            Map<String, String> headers,
            String canonicalRequest,
            String stringToSign,
            String authorization) {}

    /**
     * Compute the SigV4 headers for a request. {@code contentType} is folded
     * into the signed set only when non-empty (a bodyless GET sends no
     * Content-Type, so signing one would make AWS's recomputed canonical
     * request mismatch — matching Go, which signs only headers present on the
     * request). Host is signed as the URL authority (host:port when the port
     * is explicit), the exact value the JDK transport derives from the URI.
     */
    static Map<String, String> sign(
            String method,
            String url,
            byte[] body,
            String accessKey,
            String secretKey,
            String sessionToken,
            String region,
            String service,
            String contentType) {
        return signParts(method, url, body, accessKey, secretKey, sessionToken,
                        region, service, contentType, ZonedDateTime.now(ZoneOffset.UTC))
                .headers();
    }

    /**
     * {@link #sign} with an injected clock (CR-002): the timestamp is the only
     * non-deterministic signing input, so a fixed {@code now} makes the whole
     * signature chain reproducible for the cross-SDK golden.
     */
    static Parts signParts(
            String method,
            String url,
            byte[] body,
            String accessKey,
            String secretKey,
            String sessionToken,
            String region,
            String service,
            String contentType,
            ZonedDateTime now) {
        String datestamp = DATESTAMP.format(now);
        String amzdate = AMZ_DATE.format(now);

        URI uri = URI.create(url);
        String host = uri.getAuthority() != null ? uri.getAuthority() : "";
        String payloadHash = sha256Hex(body);

        // The signed header set (lowercased names, sorted). Values are trimmed.
        List<Map.Entry<String, String>> signed = new ArrayList<>();
        if (!contentType.isEmpty()) {
            signed.add(Map.entry("content-type", contentType));
        }
        signed.add(Map.entry("host", host));
        signed.add(Map.entry("x-amz-content-sha256", payloadHash));
        signed.add(Map.entry("x-amz-date", amzdate));
        if (!sessionToken.isEmpty()) {
            signed.add(Map.entry("x-amz-security-token", sessionToken));
        }
        signed.sort(Map.Entry.comparingByKey());

        StringBuilder signedHeadersBuilder = new StringBuilder();
        StringBuilder canonicalHeaders = new StringBuilder();
        for (Map.Entry<String, String> header : signed) {
            if (signedHeadersBuilder.length() > 0) {
                signedHeadersBuilder.append(';');
            }
            signedHeadersBuilder.append(header.getKey());
            canonicalHeaders.append(header.getKey()).append(':').append(header.getValue().trim()).append('\n');
        }
        String signedHeaders = signedHeadersBuilder.toString();

        String canonicalUri = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String canonicalQuery = canonicalQueryString(uri);

        String canonicalRequest = String.join(
                "\n", method, canonicalUri, canonicalQuery, canonicalHeaders.toString(), signedHeaders, payloadHash);

        String credentialScope = datestamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = String.join(
                "\n",
                "AWS4-HMAC-SHA256",
                amzdate,
                credentialScope,
                sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = deriveSigningKey(secretKey, datestamp, region, service);
        String signature = hexEncode(hmac(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

        String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-Amz-Date", amzdate);
        headers.put("X-Amz-Content-Sha256", payloadHash);
        headers.put("Host", host);
        headers.put("Authorization", authorization);
        if (!sessionToken.isEmpty()) {
            headers.put("X-Amz-Security-Token", sessionToken);
        }
        return new Parts(headers, canonicalRequest, stringToSign, authorization);
    }

    private static String canonicalQueryString(URI uri) {
        String query = uri.getRawQuery();
        if (query == null || query.isEmpty()) {
            return "";
        }
        List<String> pairs = new ArrayList<>(List.of(query.split("&")));
        pairs.sort(String::compareTo);
        return String.join("&", pairs);
    }

    private static byte[] deriveSigningKey(String secretKey, String datestamp, String region, String service) {
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), datestamp.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmac(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmac(kRegion, service.getBytes(StandardCharsets.UTF_8));
        return hmac(kService, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }

    private static String sha256Hex(byte[] data) {
        try {
            return hexEncode(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String hexEncode(byte[] data) {
        StringBuilder hex = new StringBuilder(data.length * 2);
        for (byte b : data) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
