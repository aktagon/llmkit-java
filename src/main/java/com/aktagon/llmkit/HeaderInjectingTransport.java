package com.aktagon.llmkit;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/*








*/
final class HeaderInjectingTransport implements HttpTransport {
    private final HttpTransport delegate;
    private final String name;
    private final String value;

    HeaderInjectingTransport(HttpTransport delegate, String name, String value) {
        this.delegate = delegate;
        this.name = name;
        this.value = value;
    }

    private Map<String, String> inject(Map<String, String> headers) {
        String lower = name.toLowerCase(Locale.ROOT);
        for (String key : headers.keySet()) {
            if (key.toLowerCase(Locale.ROOT).equals(lower)) {
                return headers;
            }
        }
        Map<String, String> merged = new LinkedHashMap<>(headers);
        merged.put(name, value);
        return merged;
    }

    @Override
    public Result postJson(String url, String body, Map<String, String> headers) {
        return delegate.postJson(url, body, inject(headers));
    }

    @Override
    public Result getText(String url, Map<String, String> headers) {
        return delegate.getText(url, inject(headers));
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
        return delegate.postMultipart(url, fields, fileField, filename, fileContentType, data, inject(headers));
    }

    @Override
    public Result postBytes(String url, byte[] body, Map<String, String> headers) {
        return delegate.postBytes(url, body, inject(headers));
    }

    @Override
    public StreamResult postJsonStreaming(String url, String body, Map<String, String> headers) {
        return delegate.postJsonStreaming(url, body, inject(headers));
    }
}
