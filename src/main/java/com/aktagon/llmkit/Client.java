package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Batch;
import com.aktagon.llmkit.providers.generated.Caching;
import com.aktagon.llmkit.providers.generated.ImageGenDef;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Request;
import java.util.ArrayList;
import java.util.List;

/*





*/
public final class Client {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;
    private final List<MiddlewareFn> defaultMiddleware;

    /**/
    public Client(ProviderName provider, String apiKey) {
        this(provider, apiKey, new JdkHttpTransport());
    }

    /**/
    Client(ProviderName provider, String apiKey, HttpTransport http) {
        this(provider, apiKey, null, http, List.of());
    }

    private Client(
            ProviderName provider,
            String apiKey,
            String baseUrlOverride,
            HttpTransport http,
            List<MiddlewareFn> defaultMiddleware) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
        this.defaultMiddleware = defaultMiddleware;
    }

    /**/
    public static Client openai(String apiKey) {
        return new Client(ProviderName.OPENAI, apiKey);
    }

    /*


*/
    public Client baseUrl(String url) {
        return new Client(provider, apiKey, url, http, defaultMiddleware);
    }

    /*







*/
    public Client addHeader(String name, String value) {
        return new Client(
                provider,
                apiKey,
                baseUrlOverride,
                new HeaderInjectingTransport(http, name, value),
                defaultMiddleware);
    }

    /*






*/
    public Client addTelemetry(Telemetry telemetry) {
        List<MiddlewareFn> next = new ArrayList<>(defaultMiddleware);
        next.add(TelemetryRuntime.makeMiddleware(telemetry));
        return new Client(provider, apiKey, baseUrlOverride, http, next);
    }

    /*





*/
    Client addMiddleware(MiddlewareFn hook) {
        List<MiddlewareFn> next = new ArrayList<>(defaultMiddleware);
        next.add(hook);
        return new Client(provider, apiKey, baseUrlOverride, http, next);
    }

    /**/
    public Text text() {
        Text builder = Text.root(provider, apiKey, baseUrlOverride, http);
        for (MiddlewareFn hook : defaultMiddleware) {
            builder = builder.addMiddleware(hook);
        }
        return builder;
    }

    /**/
    public Agent agent() {
        Agent agent = new Agent(provider, apiKey, baseUrlOverride, http);
        for (MiddlewareFn hook : defaultMiddleware) {
            agent.addMiddleware(hook);
        }
        return agent;
    }

    /**/
    public Image image() {
        Image builder = Image.root(provider, apiKey, baseUrlOverride, http);
        for (MiddlewareFn hook : defaultMiddleware) {
            builder = builder.addMiddleware(hook);
        }
        return builder;
    }

    /**/
    public Speech speech() {
        return Speech.root(provider, apiKey, baseUrlOverride, http);
    }

    /**/
    public Music music() {
        Music builder = Music.root(provider, apiKey, baseUrlOverride, http);
        for (MiddlewareFn hook : defaultMiddleware) {
            builder = builder.addMiddleware(hook);
        }
        return builder;
    }

    /**/
    public Video video() {
        Video builder = Video.root(provider, apiKey, baseUrlOverride, http);
        for (MiddlewareFn hook : defaultMiddleware) {
            builder = builder.addMiddleware(hook);
        }
        return builder;
    }

    /*






*/
    public Transcription transcription() {
        return Transcription.root(provider, apiKey, baseUrlOverride, http);
    }

    /*




*/
    public Models models() {
        //
        //
        return Models.root(provider, apiKey, baseUrlOverride, http, defaultMiddleware);
    }

    /*



*/
    public Providers providers() {
        return new Providers(provider);
    }

    /**/
    public Upload upload() {
        return Upload.root(provider, apiKey, baseUrlOverride, http);
    }

    /*









*/
    public boolean supports(Capability capability) {
        return switch (capability) {
            case CACHING -> Caching.config(provider) != null;
            case BATCHING -> Batch.config(provider) != null;
            case FILE_UPLOAD -> Request.fileUploadConfig(provider) != null;
            case IMAGE_GENERATION -> ImageGenDef.imageGenConfig(provider) != null;
            default -> true;
        };
    }
}
