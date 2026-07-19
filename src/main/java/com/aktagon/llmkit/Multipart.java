package com.aktagon.llmkit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/*






*/
final class Multipart {
    private Multipart() {}

    record Encoded(String boundary, byte[] payload) {}

    /*




*/
    private static String escapeQuotes(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "");
    }

    static Encoded encode(
            Map<String, String> fields,
            String fileField,
            String filename,
            String fileContentType,
            byte[] data) {
        String boundary = "llmkit-boundary-" + UUID.randomUUID();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        try {
            for (Map.Entry<String, String> field : fields.entrySet()) {
                payload.write(("--" + boundary + "\r\n"
                        + "Content-Disposition: form-data; name=\"" + escapeQuotes(field.getKey()) + "\"\r\n\r\n"
                        + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
            }
            payload.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + escapeQuotes(fileField) + "\"; filename=\"" + escapeQuotes(filename) + "\"\r\n"
                    + "Content-Type: " + fileContentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            payload.write(data);
            payload.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new TransportException(e.getMessage(), e);
        }
        return new Encoded(boundary, payload.toByteArray());
    }
}
