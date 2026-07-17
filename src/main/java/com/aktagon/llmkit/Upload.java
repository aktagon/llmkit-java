package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.File;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Providers;
import com.aktagon.llmkit.providers.generated.Request;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The Files API upload builder (ADR-060 / CR-004). {@code
 * client.upload().path(p).run()} uploads a file to the provider and returns
 * a {@link File} handle to attach to a later prompt via {@code .file(id)}.
 * {@code path()} and {@code bytes()} are mutually exclusive — exactly one
 * must be set; {@code filename()} is required with {@code bytes()} (no path
 * to derive a name from) and overrides the derived name with {@code path()};
 * {@code mimeType()} overrides extension-based detection. Fires the {@code
 * upload} MiddlewareOp. Mirrors Go {@code Upload}/{@code Run}, TS/Python/Rust
 * {@code upload().run()}, Swift {@code Upload}/{@code run()}.
 */
public final class Upload {
    private static final long MAX_UPLOAD_BYTES = 1L << 30; // 1GB

    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final UploadOptions options;

    private Upload(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            UploadOptions options) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.options = options;
    }

    static Upload root(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        return new Upload(provider, apiKey, baseUrlOverride, http, new UploadOptions());
    }

    /**
     * Read the upload payload from a filesystem path. The multipart filename
     * defaults to the path's last component unless {@code filename()}
     * overrides it.
     */
    public Upload path(String value) {
        return withOptions(o -> o.path = value);
    }

    /** Upload the given bytes directly. {@code filename()} is required in this mode. */
    public Upload bytes(byte[] value) {
        byte[] copy = value == null ? null : value.clone();
        return withOptions(o -> o.bytes = copy);
    }

    /** Override the multipart filename. */
    public Upload filename(String value) {
        return withOptions(o -> o.filename = value);
    }

    /** Override the file part's Content-Type (else inferred from the filename). */
    public Upload mimeType(String value) {
        return withOptions(o -> o.mimeType = value);
    }

    /** Register a middleware hook for this upload (observes/vetoes the {@code upload} op). */
    public Upload addMiddleware(MiddlewareFn hook) {
        return withOptions(o -> o.middleware.add(hook));
    }

    /** Upload the configured file and return its {@link File} handle. */
    public File run() {
        boolean hasPath = options.path != null && !options.path.isEmpty();
        boolean hasBytes = options.bytes != null;
        if (!hasPath && !hasBytes) {
            throw new ValidationException("Upload", "exactly one of path() or bytes() must be set");
        }
        if (hasPath && hasBytes) {
            throw new ValidationException("Upload", "path() and bytes() are mutually exclusive");
        }

        byte[] data;
        String name;
        if (hasPath) {
            Path path = Path.of(options.path);
            try {
                // Stat first so pathologically large files reject before
                // allocating — well above any provider's real upload limit,
                // but blocks a trivial OOM via path("/dev/zero") (mirrors
                // Go's guard).
                long size = Files.size(path);
                if (size > MAX_UPLOAD_BYTES) {
                    throw new ValidationException(
                            "path", "file too large: " + size + " bytes exceeds " + MAX_UPLOAD_BYTES + " limit");
                }
                data = Files.readAllBytes(path);
            } catch (IOException e) {
                throw new ValidationException("path", "cannot read " + options.path + ": " + e.getMessage());
            }
            if (options.filename != null && !options.filename.isEmpty()) {
                name = options.filename;
            } else {
                Path last = path.getFileName();
                if (last == null) {
                    throw new ValidationException(
                            "path", "cannot derive a filename from " + options.path + "; set filename()");
                }
                name = last.toString();
            }
        } else {
            if (options.filename == null || options.filename.isEmpty()) {
                throw new ValidationException("Upload", "filename() is required when bytes() is set");
            }
            data = options.bytes;
            name = options.filename;
        }

        return uploadData(provider, apiKey, baseUrlOverride, http, data, name, options.mimeType, options.middleware);
    }

    /** Clone-on-chain: copy the options, mutate, return a fresh builder. */
    private Upload withOptions(Consumer<UploadOptions> mutate) {
        UploadOptions copy = options.copy();
        mutate.accept(copy);
        return new Upload(provider, apiKey, baseUrlOverride, http, copy);
    }

    // --- Transport (fire + multipart) ---

    /** Fires the {@code upload} op around the multipart POST (mirrors Rust {@code upload_with_data}). */
    private static File uploadData(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            byte[] data,
            String filename,
            String mime,
            List<MiddlewareFn> middleware) {
        Providers.Spec config = Providers.config(provider);
        Request.FileUploadDef upload = Request.fileUploadConfig(provider);
        if (upload == null) {
            throw new ValidationException("provider", "file upload not supported: " + config.slug);
        }
        String model = RequestBuilder.resolveModel(config, null);

        Event baseEvent = Event.of(MiddlewareOp.UPLOAD, config.slug, model);
        long startNanos = System.nanoTime();
        Middleware.firePre(middleware, baseEvent);
        try {
            File file = send(config, upload, apiKey, baseUrlOverride, http, data, filename, mime);
            Middleware.firePost(
                    middleware, baseEvent.toPost("", null, null, Middleware.elapsedMillis(startNanos)));
            return file;
        } catch (RuntimeException e) {
            Middleware.firePost(
                    middleware, baseEvent.toPost("", null, e.getMessage(), Middleware.elapsedMillis(startNanos)));
            throw e;
        }
    }

    /** The multipart POST + response-path parse (mirrors Rust {@code upload_file_inner}). */
    private static File send(
            Providers.Spec config,
            Request.FileUploadDef upload,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            byte[] data,
            String filename,
            String mime) {
        String base = baseUrlOverride != null ? baseUrlOverride : config.baseUrl;
        String url = base + upload.endpoint;
        if ("QueryParamKey".equals(config.authScheme) && !config.authQueryParam.isEmpty()) {
            String separator = url.contains("?") ? "&" : "?";
            url += separator + config.authQueryParam + "=" + apiKey;
        }

        Map<String, String> headers = RequestBuilder.buildAuthHeaders(config, apiKey);
        if (!upload.betaHeader.isEmpty()) {
            headers.put("anthropic-beta", upload.betaHeader);
        }

        String mimeType = mime != null && !mime.isEmpty() ? mime : detectMimeType(filename);

        Map<String, String> fields = new LinkedHashMap<>();
        if (!upload.extraFieldsJson.isEmpty()) {
            JsonElement parsed = Json.parse(upload.extraFieldsJson);
            if (parsed.isJsonObject()) {
                for (Map.Entry<String, JsonElement> entry : parsed.getAsJsonObject().entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        fields.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
        }

        // Google carries the filename as a JSON metadata form field + a protocol header.
        if ("ChatGoogle".equals(config.chatWireShape)) {
            JsonObject file = new JsonObject();
            file.addProperty("display_name", filename);
            JsonObject metadata = new JsonObject();
            metadata.add("file", file);
            fields.put("metadata", Json.serialize(metadata));
            headers.put("X-Goog-Upload-Protocol", "multipart");
        }

        HttpTransport.Result result =
                http.postMultipart(url, fields, upload.fieldName, filename, mimeType, data, headers);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw ResponseParser.parseError(config, result.statusCode(), result.body());
        }

        JsonElement parsed = Json.parse(new String(result.body(), StandardCharsets.UTF_8));
        String id = upload.responseIdPath.isEmpty() ? "" : Json.stringAt(parsed, upload.responseIdPath);
        String uri = upload.responseUriPath.isEmpty() ? "" : Json.stringAt(parsed, upload.responseUriPath);
        String name = upload.responseNamePath.isEmpty() ? filename : Json.stringAt(parsed, upload.responseNamePath);
        String mimeOut =
                upload.responseMimePath.isEmpty() ? mimeType : Json.stringAt(parsed, upload.responseMimePath);
        return new File(id, uri, mimeOut, name);
    }

    /**
     * Extension-based MIME fallback when {@code mimeType()} is unset (mirrors
     * Go's {@code detectMimeType}). The dominant provider (Anthropic)
     * overrides mimeType from the response anyway; this sets the request
     * part's Content-Type.
     */
    private static String detectMimeType(String filename) {
        int dot = filename.lastIndexOf('.');
        String ext = dot >= 0 ? filename.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "json" -> "application/json";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            case "csv" -> "text/csv";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }
}
