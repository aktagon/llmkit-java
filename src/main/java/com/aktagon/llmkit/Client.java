package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderName;







public final class Client {
    private final ProviderName provider;
    private final String apiKey;
    private final String baseUrlOverride;
    private final HttpTransport http;


    public Client(ProviderName provider, String apiKey) {
        this(provider, apiKey, new JdkHttpTransport());
    }


    Client(ProviderName provider, String apiKey, HttpTransport http) {
        this.provider = provider;
        this.apiKey = apiKey;
        this.baseUrlOverride = null;
        this.http = http;
    }


    public static Client openai(String apiKey) {
        return new Client(ProviderName.OPENAI, apiKey);
    }


    public Text text() {
        return Text.root(provider, apiKey, baseUrlOverride, http);
    }
}
