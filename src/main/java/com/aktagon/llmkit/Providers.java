package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderInfo;
import com.aktagon.llmkit.providers.generated.ProviderName;
import java.util.List;

/*






*/
public final class Providers {
    private final ProviderName provider;

    Providers(ProviderName provider) {
        this.provider = provider;
    }

    /**/
    public List<ProviderInfo> list() {
        return Models.catalogueProvidersList(provider);
    }
}
