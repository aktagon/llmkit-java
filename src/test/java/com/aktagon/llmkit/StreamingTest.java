package com.aktagon.llmkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.Spliterators;
import org.junit.jupiter.api.Test;

/**
 * Stream lifecycle: the SSE line Stream owns the response subscription, so it
 * must be closed on the done-sentinel early return (which fires on essentially
 * every successful OpenAI stream), and a mid-stream network failure must
 * surface as the typed TransportException, not the JDK's raw
 * UncheckedIOException.
 */
class StreamingTest {

    /** Transport whose streamed body reports whether it was closed. */
    private static final class ClosableStreamTransport implements HttpTransport {
        final AtomicBoolean closed = new AtomicBoolean(false);
        private final Stream<String> lines;

        ClosableStreamTransport(Stream<String> lines) {
            this.lines = lines.onClose(() -> closed.set(true));
        }

        @Override
        public StreamResult postJsonStreaming(String url, String body, Map<String, String> headers) {
            return new StreamResult(200, lines);
        }

        @Override
        public Result postJson(String url, String body, Map<String, String> headers) {
            throw new UnsupportedOperationException("stream-only transport");
        }

        @Override
        public Result getText(String url, Map<String, String> headers) {
            throw new UnsupportedOperationException("stream-only transport");
        }

        @Override
        public Result postMultipart(
                String url,
                Map<String, String> fields,
                String fileField,
                String filename,
                String fileContentType,
                byte[] data,
                Map<String, String> headers) {
            throw new UnsupportedOperationException("stream-only transport");
        }

        @Override
        public Result postBytes(String url, byte[] body, Map<String, String> headers) {
            throw new UnsupportedOperationException("stream-only transport");
        }
    }

    @Test
    void doneSentinelReturnClosesLineStream() {
        ClosableStreamTransport transport = new ClosableStreamTransport(Stream.of(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":\"stop\"}]}",
                "data: [DONE]",
                "data: {\"never\":\"consumed\"}"));

        StringBuilder deltas = new StringBuilder();
        Response response = new Client(ProviderName.OPENAI, "test-key", transport)
                .text().model("gpt-4o-mini").stream("Say hello.", deltas::append);

        assertEquals("Hello", deltas.toString());
        assertEquals("Hello", response.text());
        assertTrue(transport.closed.get(), "line stream must be closed on the [DONE] early return");
    }

    @Test
    void midStreamNetworkFailureSurfacesAsTransportException() {
        Iterator<String> failing = new Iterator<>() {
            private int served = 0;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public String next() {
                if (served++ == 0) {
                    return "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}";
                }
                throw new UncheckedIOException(new IOException("connection reset by peer"));
            }
        };
        ClosableStreamTransport transport = new ClosableStreamTransport(
                StreamSupport.stream(Spliterators.spliteratorUnknownSize(failing, 0), false));

        TransportException e = assertThrows(
                TransportException.class,
                () -> new Client(ProviderName.OPENAI, "test-key", transport)
                        .text().model("gpt-4o-mini").stream("Say hello.", delta -> {}));
        assertTrue(e.getMessage().contains("stream interrupted"));
        assertInstanceOf(UncheckedIOException.class, e.getCause());
        assertTrue(transport.closed.get(), "line stream must be closed on the failure path");
    }
}
