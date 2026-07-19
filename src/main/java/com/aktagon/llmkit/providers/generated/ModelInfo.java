//

package com.aktagon.llmkit.providers.generated;

import com.aktagon.llmkit.Capability;
import com.google.gson.JsonElement;

/*

*/
public record ModelInfo(String id, ProviderName provider, java.util.List<Capability> capabilities, String displayName, String description, long contextWindow, long maxOutput, long created, JsonElement raw) {}
