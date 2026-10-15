package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.RequestVariant;
import java.util.List;

/** Picks a capability config's wire request by the inputs it carries (ADR-096). */
final class RequestVariants {
    private RequestVariants() {}

    /**
     * {@code imageCarries} when the request holds at least one image part,
     * else TextOnly. The capability names what its image parts are:
     * WithReferenceImages for image, WithSeedFrame for video. A config with no
     * variant for those inputs cannot send the request, so the caller gets a
     * {@link ValidationException} on "parts".
     */
    static RequestVariant select(
            String provider, List<RequestVariant> variants, long imageCount, String imageCarries) {
        String carries = imageCount > 0 ? imageCarries : RequestVariant.TEXT_ONLY;
        for (RequestVariant variant : variants) {
            if (variant.carries().equals(carries)) {
                return variant;
            }
        }
        throw new ValidationException("parts", provider + " has no " + carries + " request");
    }
}
