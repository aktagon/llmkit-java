package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aktagon.llmkit.providers.generated.File;
import com.aktagon.llmkit.providers.generated.ProviderName;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The Files API (CR-004, ADR-060 parity): {@code client.upload().run()}
 * uploads bytes or a path and returns a {@link File} handle, firing the
 * {@code upload} MiddlewareOp. Asserts the multipart request body, contract
 * headers, response-path parse, validation, and the middleware fire against a
 * capturing transport. Mirrors Swift's {@code UploadTests}.
 */
class UploadTest {

    @Test
    void uploadBytesParsesFileAndBuildsMultipart() throws IOException {
        CapturingTransport transport = new CapturingTransport()
                .withResponse(200, "{\"id\":\"file_abc123\",\"filename\":\"notes.pdf\",\"mime_type\":\"application/pdf\"}");
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", transport);

        File file = client.upload()
                .bytes("hello".getBytes(StandardCharsets.UTF_8))
                .filename("notes.pdf")
                .run();

        assertEquals("file_abc123", file.id());
        assertEquals("notes.pdf", file.name());
        assertEquals("application/pdf", file.mimeType());

        String body = transport.capturedBody;
        assertTrue(body.contains("name=\"file\"; filename=\"notes.pdf\""), body);
        assertTrue(body.contains("Content-Type: application/pdf"), body);
        assertTrue(body.contains("hello"), body);

        assertEquals("files-api-2025-04-14", transport.capturedHeaders.get("anthropic-beta"));
        assertEquals("https://api.anthropic.com/v1/files", transport.capturedUrl);
    }

    @Test
    void uploadPathDerivesFilename() throws IOException {
        CapturingTransport transport = new CapturingTransport()
                .withResponse(200, "{\"id\":\"file_xyz\",\"filename\":\"report.pdf\",\"mime_type\":\"application/pdf\"}");
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", transport);

        Path tmp = Files.createTempFile("llmkit-upload-", "-report.pdf");
        try {
            Files.write(tmp, "report bytes".getBytes(StandardCharsets.UTF_8));
            File file = client.upload().path(tmp.toString()).run();

            assertEquals("file_xyz", file.id());
            String body = transport.capturedBody;
            assertTrue(body.contains("filename=\"" + tmp.getFileName() + "\""), body);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    void extraFieldsPurposeForOpenAI() throws IOException {
        CapturingTransport transport =
                new CapturingTransport().withResponse(200, "{\"id\":\"file_oai\",\"filename\":\"data.jsonl\"}");
        Client client = new Client(ProviderName.OPENAI, "test-key", transport);

        File file = client.upload().bytes("{}".getBytes(StandardCharsets.UTF_8)).filename("data.jsonl").run();

        assertEquals("file_oai", file.id());
        // OpenAI's FileUploadDef carries {"purpose":"assistants"} as a form field.
        String body = transport.capturedBody;
        assertTrue(body.contains("name=\"purpose\""), body);
        assertTrue(body.contains("assistants"), body);
    }

    @Test
    void firesUploadMiddleware() throws IOException {
        CapturingTransport transport = new CapturingTransport()
                .withResponse(200, "{\"id\":\"file_1\",\"filename\":\"a.txt\",\"mime_type\":\"text/plain\"}");
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", transport);
        List<Event> events = new ArrayList<>();
        MiddlewareFn hook = event -> {
            events.add(event);
            return null;
        };

        client.upload().addMiddleware(hook).bytes("x".getBytes(StandardCharsets.UTF_8)).filename("a.txt").run();

        assertEquals(2, events.size());
        assertEquals(MiddlewareOp.UPLOAD, events.get(0).op);
        assertEquals(MiddlewarePhase.PRE, events.get(0).phase);
        assertEquals("anthropic", events.get(0).provider);
        assertEquals(MiddlewarePhase.POST, events.get(1).phase);
        assertEquals(null, events.get(1).err);
    }

    @Test
    void validationRejectsNeitherPathNorBytes() {
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", new CapturingTransport());
        assertThrows(ValidationException.class, () -> client.upload().run());
    }

    @Test
    void validationRejectsBothPathAndBytes() {
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", new CapturingTransport());
        assertThrows(
                ValidationException.class,
                () -> client.upload().path("/tmp/x").bytes("y".getBytes(StandardCharsets.UTF_8)).filename("y").run());
    }

    @Test
    void validationRejectsBytesWithoutFilename() {
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", new CapturingTransport());
        assertThrows(
                ValidationException.class,
                () -> client.upload().bytes("z".getBytes(StandardCharsets.UTF_8)).run());
    }

    @Test
    void emptyBytesUploadIsSetNotMisdiagnosed() {
        // bytes(new byte[0]) is a legitimate empty file, not "bytes unset".
        CapturingTransport transport = new CapturingTransport()
                .withResponse(200, "{\"id\":\"file_empty\",\"filename\":\"empty.txt\",\"mime_type\":\"text/plain\"}");
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", transport);

        File file = client.upload().bytes(new byte[0]).filename("empty.txt").run();

        assertEquals("file_empty", file.id());
        assertTrue(transport.capturedBody.contains("filename=\"empty.txt\""), transport.capturedBody);
    }

    @Test
    void unreadablePathIsAValidationError() {
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", new CapturingTransport());

        ValidationException err = assertThrows(
                ValidationException.class,
                () -> client.upload().path("/nonexistent/llmkit-upload-missing.pdf").run());

        assertEquals("path", err.field());
    }

    @Test
    void bytesAreCopiedNotAliased() {
        CapturingTransport transport = new CapturingTransport()
                .withResponse(200, "{\"id\":\"file_copy\",\"filename\":\"data.txt\",\"mime_type\":\"text/plain\"}");
        Client client = new Client(ProviderName.ANTHROPIC, "test-key", transport);

        byte[] payload = "original".getBytes(StandardCharsets.UTF_8);
        Upload upload = client.upload().bytes(payload).filename("data.txt");
        payload[0] = 'X'; // caller mutation after chaining must not leak in

        upload.run();
        assertTrue(transport.capturedBody.contains("original"), transport.capturedBody);
    }

    @Test
    void unsupportedProviderThrows() {
        Client client = new Client(ProviderName.OLLAMA, "test-key", new CapturingTransport());
        assertThrows(
                ValidationException.class,
                () -> client.upload().bytes("q".getBytes(StandardCharsets.UTF_8)).filename("q.txt").run());
    }
}
