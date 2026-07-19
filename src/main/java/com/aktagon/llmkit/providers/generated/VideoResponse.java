//

package com.aktagon.llmkit.providers.generated;

import com.aktagon.llmkit.Usage;
import com.google.gson.JsonElement;

/*

*/
public record VideoResponse(java.util.List<VideoData> videos, Usage usage, String finishReason, String finishMessage, JsonElement raw) {}
