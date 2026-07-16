package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.Batch;
import com.aktagon.llmkit.providers.generated.Caching;
import com.aktagon.llmkit.providers.generated.ImageGenDef;
import com.aktagon.llmkit.providers.generated.ProviderName;
import com.aktagon.llmkit.providers.generated.Request;








public final class Client {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;


    public Client(ProviderName provider, String apiKey) {
        this(provider, apiKey, new JdkHttpTransport());
    }


    Client(ProviderName provider, String apiKey, HttpTransport http) {
        this(provider, apiKey, null, http);
    }

    private Client(ProviderName provider, String apiKey, String baseUrlOverride, HttpTransport http) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = baseUrlOverride;
        this.http = http;
    }


    public static Client openai(String apiKey) {
        return new Client(ProviderName.OPENAI, apiKey);
    }





    public Client baseUrl(String url) {
        return new Client(provider, apiKey, url, http);
    }


    public Text text() {
        return Text.root(provider, apiKey, baseUrlOverride, http);
    }












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
