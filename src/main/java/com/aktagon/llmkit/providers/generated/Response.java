//

package com.aktagon.llmkit.providers.generated;

import com.aktagon.llmkit.Usage;
import com.google.gson.JsonElement;

/*

*/
public record Response(String text, Usage usage, String finishReason, String finishMessage, JsonElement raw) {}
