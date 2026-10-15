package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.RequestVariant;
import java.util.HashSet;
import java.util.List;

/** Picks a capability config's wire request by the inputs it carries (ADR-096). */
final class RequestVariants {
    private RequestVariants() {}

    /**
     * The variant whose carried inputs equal {@code carries}, in any order. A
     * first-and-last-frame request carries two inputs. A config with no
     * variant for those inputs cannot send the request, so the caller gets a
     * {@link ValidationException} on "parts".
     */
    static RequestVariant select(String provider, List<RequestVariant> variants, List<String> carries) {
        for (RequestVariant variant : variants) {
            if (variant.carries().size() == carries.size()
                    && new HashSet<>(variant.carries()).equals(new HashSet<>(carries))) {
                return variant;
            }
        }
        throw new ValidationException("parts", provider + " has no " + String.join("+", carries) + " request");
    }

    /**
     * The carried input of a request with {@code imageCount} image parts:
     * {@code imageInput} when it holds at least one, else TextOnly. The
     * capability names what its image parts are: WithReferenceImages for
     * image, WithSeedFrame for video.
     */
    static List<String> partsCarry(long imageCount, String imageInput) {
        return List.of(imageCount > 0 ? imageInput : RequestVariant.TEXT_ONLY);
    }
}
