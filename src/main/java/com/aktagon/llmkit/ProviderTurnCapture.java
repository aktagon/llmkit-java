package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ProviderTurn;
import com.aktagon.llmkit.providers.generated.Providers;
import java.util.ArrayList;
import java.util.List;

/**
 * Verbatim assistant-turn capture (ADR-085).
 *
 * <p>llmkit keeps a canonical projection of every turn — role, content, tool
 * calls — and used to rebuild the next request's assistant turn from it. That
 * projection is lossy in three ways (ADR-085 §1): it has nowhere to put
 * reasoning, it drops assistant prose that accompanies a tool call, and it has
 * no slot for per-part provider metadata. The fix is not a richer projection but
 * a second representation alongside it: keep the provider's own bytes for the
 * turn and send those back unchanged.
 *
 * <p>Nothing here parses the payload. The only structure this class reads is the
 * path down to the turn — everything at and below it is carried as the provider
 * wrote it.
 *
 * <p>Java-specific note. A parsed {@code JsonElement} has no memory of the
 * source text, and re-serializing it emits Gson's rendering. So capture SLICES
 * the response text: the walk below finds the span of the value at the path and
 * keeps that substring. On the SEND leg the splice re-parses that string, because
 * that is the type the request body is assembled in — object member order
 * survives (Gson objects are insertion-ordered), which is what ADR-085 RSN-002's
 * amendment permits (verbatim at REST is the guarantee; byte-identical
 * transmission is a Go-only property).
 */
final class ProviderTurnCapture {
    private ProviderTurnCapture() {}

    /** A field name plus an array index, or -1 when the segment carries none. */
    record Segment(String field, int index) {}

    /**
     * Splits one dot-notation path segment into a field name and an array index:
     * {@code "choices[0]"} -> {@code ("choices", 0)}; {@code "message"} ->
     * {@code ("message", -1)}.
     *
     *
     * its single parser here, so capture cannot drift from the path facts it
     * reads. A malformed segment yields the whole segment as a field name and no
     * index, which resolves to "no such field" one step later — never a silently
     * widened match to the whole array, which is what a negative index would read
     * back as.
     */
    static Segment splitPathSegment(String part) {
        int bracket = part.indexOf('[');
        if (bracket < 0 || !part.endsWith("]")) {
            return new Segment(part, -1);
        }
        String inner = part.substring(bracket + 1, part.length() - 1);
        if (inner.isEmpty()) {
            return new Segment(part, -1);
        }
        for (int i = 0; i < inner.length(); i++) {
            // Digits only: "+1", "-1" and " 1" all parse under Integer.parseInt or
            // read back as a negative index.
            if (inner.charAt(i) < '0' || inner.charAt(i) > '9') {
                return new Segment(part, -1);
            }
        }
        return new Segment(part.substring(0, bracket), Integer.parseInt(inner));
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static int skipWhitespace(String text, int i) {
        while (i < text.length() && isWhitespace(text.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The index just past the JSON string starting at {@code i}, or -1. */
    private static int skipString(String text, int i) {
        if (i >= text.length() || text.charAt(i) != '"') {
            return -1;
        }
        i++;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            if (c == '"') {
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    /**
     * The index just past the JSON value starting at {@code i}, or -1 when the
     * text there is not a value. Objects and arrays are walked by nesting depth,
     * which is why the string scan above has to be exact — a {@code }} inside a
     * string must not close an object.
     */
    private static int skipValue(String text, int i) {
        i = skipWhitespace(text, i);
        if (i >= text.length()) {
            return -1;
        }
        char ch = text.charAt(i);
        if (ch == '"') {
            return skipString(text, i);
        }
        if (ch == '{' || ch == '[') {
            int depth = 0;
            while (i < text.length()) {
                char c = text.charAt(i);
                if (c == '"') {
                    int end = skipString(text, i);
                    if (end < 0) {
                        return -1;
                    }
                    i = end;
                    continue;
                }
                if (c == '{' || c == '[') {
                    depth++;
                } else if (c == '}' || c == ']') {
                    depth--;
                    if (depth == 0) {
                        return i + 1;
                    }
                }
                i++;
            }
            return -1;
        }
        // A literal or number: everything up to the next structural character.
        int start = i;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == ',' || c == '}' || c == ']' || isWhitespace(c)) {
                break;
            }
            i++;
        }
        return i > start ? i : -1;
    }

    /** The half-open span of {@code field}'s value in the object at {@code start}. */
    private static int[] spanOfMember(String text, int start, String field) {
        int i = skipWhitespace(text, start);
        if (i >= text.length() || text.charAt(i) != '{') {
            return null;
        }
        i++;
        while (true) {
            i = skipWhitespace(text, i);
            if (i >= text.length() || text.charAt(i) == '}') {
                return null;
            }
            int keyEnd = skipString(text, i);
            if (keyEnd < 0) {
                return null;
            }
            // The key is a JSON string, so the SDK's own parser decodes its escapes.
            String key;
            try {
                key = Json.parse(text.substring(i, keyEnd)).getAsString();
            } catch (RuntimeException e) {
                return null;
            }
            i = skipWhitespace(text, keyEnd);
            if (i >= text.length() || text.charAt(i) != ':') {
                return null;
            }
            int valueStart = skipWhitespace(text, i + 1);
            int valueEnd = skipValue(text, valueStart);
            if (valueEnd < 0) {
                return null;
            }
            if (key.equals(field)) {
                return new int[] {valueStart, valueEnd};
            }
            i = skipWhitespace(text, valueEnd);
            if (i >= text.length() || text.charAt(i) != ',') {
                return null;
            }
            i++;
        }
    }

    /** The half-open span of element {@code index} in the array at {@code start}. */
    private static int[] spanOfElement(String text, int start, int index) {
        int i = skipWhitespace(text, start);
        if (i >= text.length() || text.charAt(i) != '[') {
            return null;
        }
        i++;
        int position = 0;
        while (true) {
            i = skipWhitespace(text, i);
            if (i >= text.length() || text.charAt(i) == ']') {
                return null;
            }
            int valueEnd = skipValue(text, i);
            if (valueEnd < 0) {
                return null;
            }
            if (position == index) {
                return new int[] {i, valueEnd};
            }
            position++;
            i = skipWhitespace(text, valueEnd);
            if (i >= text.length() || text.charAt(i) != ',') {
                return null;
            }
            i++;
        }
    }

    /**
     * The VERBATIM JSON text of the value at {@code path}, or null when the path
     * does not resolve.
     *
     * <p>The distinction from {@link Json#at} is the whole point: that walker
     * descends a parsed tree, so re-serializing its result emits Gson's rendering
     * of the value rather than the provider's.
     */
    static String extractRawJsonPath(String text, String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int start = 0;
        int end = text.length();
        for (String part : path.split("\\.", -1)) {
            Segment segment = splitPathSegment(part);
            if (!segment.field().isEmpty()) {
                int[] span = spanOfMember(text, start, segment.field());
                if (span == null) {
                    return null;
                }
                start = span[0];
                end = span[1];
            }
            if (segment.index() >= 0) {
                int[] span = spanOfElement(text, start, segment.index());
                if (span == null) {
                    return null;
                }
                start = span[0];
                end = span[1];
            }
        }
        return text.substring(start, end);
    }

    /**
     * Where one replayable assistant turn sits in a response body for this
     * provider under this wire shape, or {@code ""} when the shape declares no
     * position.
     *
     * <p>An empty result is a DECLARED absence, not a missing lookup:
     * {@code ChatBedrock} carries {@code assistantTurnUnanchored} rather
     * than a path, because nobody has probed what an assistant turn looks like on
     *
     * neither, so {@code ""} here always means "declared unanchored" and never
     * "somebody forgot".
     */
    static String assistantTurnPath(Providers.Spec config, String chatWireShape) {
        for (Providers.ChatProtocol protocol : config.chatProtocols) {
            if (protocol.wireShape.equals(chatWireShape)) {
                return protocol.assistantTurnPath;
            }
        }
        return "";
    }

    /**
     * The shape a response was produced under. An empty argument means the caller
     * did not route through a protocol opt-in — the batch path passes {@code ""}
     * because batch is Chat-Completions-only (ADR-055) — so the provider's default
     * shape applies.
     */
    private static String effectiveChatWireShape(Providers.Spec config, String chatWireShape) {
        return chatWireShape == null || chatWireShape.isEmpty() ? config.chatWireShape : chatWireShape;
    }

    /**
     * Lifts the assistant turn out of a response body, or returns null when this
     * shape declares no turn position or the body carries nothing there.
     */
    static ProviderTurn capture(String text, Providers.Spec config, String chatWireShape) {
        String shape = effectiveChatWireShape(config, chatWireShape);
        String wire = extractRawJsonPath(text, assistantTurnPath(config, shape));
        if (wire == null) {
            return null;
        }
        // A JSON null at the path is the provider declining to send a turn, not a
        // turn whose content is null — Google nulls candidates[0].content on a
        // safety block, and OpenAI-compatible proxies null choices[0].message on a
        // content filter. A bare `null` is four characters of a perfectly good JSON
        // value, so an emptiness check alone captures it and the next request
        // appends a bare null to the message array, which is a 400.
        String trimmed = wire.trim();
        if (trimmed.isEmpty() || trimmed.equals("null")) {
            return null;
        }
        return new ProviderTurn(shape, wire);
    }

    /**
     * The RSN-006 boundary: a captured payload is replayed only under the shape
     * that produced it, and a mismatch drops it and reconstructs the turn from the
     * canonical projection instead.
     *
     * <p>One unconditional rule, applied once per request where the config and the
     * message list first meet, so no transform has to remember the check. The
     * draft ADR made this branch on whether the provider mandates the echo and
     * raised an error on the mandating ones; RESEARCH-017 measured that set to be
     * empty, so only the drop arm was ever reachable.
     *
     * <p>Dropping is the safe direction here, and the measurement is why: every
     * probed provider ACCEPTS a request with the payload omitted, while a mangled
     * payload is the single 400 anywhere in the matrix. Replaying an Anthropic
     * block array into Google's contents array would be exactly that mangling.
     */
    static List<Msg> resolveTurns(List<Msg> msgs, Providers.Spec config, String wireShape) {
        List<Msg> out = new ArrayList<>(msgs.size());
        for (Msg msg : msgs) {
            // Two conditions, not one. Matching the shape is not enough: the shape
            // must also DECLARE a turn position. A payload claiming an unanchored
            // shape can only come from caller-supplied data, and the transform for
            // such a shape has no replay arm — so without the second check, a
            // history carrying a ChatBedrock payload reaches a builder with nowhere
            // to put it.
            if (msg instanceof Msg.Turn turn
                    && !(turn.shape().equals(wireShape)
                            && !assistantTurnPath(config, turn.shape()).isEmpty())) {
                out.add(turn.fallback());
            } else {
                out.add(msg);
            }
        }
        return out;
    }
}
