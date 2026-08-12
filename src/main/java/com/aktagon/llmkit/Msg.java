package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ToolCall;
import com.aktagon.llmkit.providers.generated.ToolResult;
import java.util.List;

/**
 * The internal message representation: a sum that is <em>exactly one of</em>
 * text, media (text + image/file parts), tool-calls, or tool-result (ADR-026
 * PIPE-007). The public {@code Message} is a flat product that can encode an
 * illegal multi-carrier combination; this sealed interface cannot, so the
 * transforms dispatch with an exhaustive switch. Mirrors Swift's
 * {@code Transforms.Msg} enum.
 */
sealed interface Msg {
    record Text(String role, String text) implements Msg {}

    /** A user turn carrying accumulated image/file parts (ADR-060). */
    record Media(String role, String text, List<InputImage> images, List<FileRef> files) implements Msg {}

    record Calls(List<ToolCall> calls) implements Msg {}

    record ToolOutput(ToolResult result) implements Msg {}

    /**
     * An assistant turn the provider itself serialized, replayed verbatim
     * instead of rebuilt (ADR-085). It carries the projection it replaces so
     * {@code ProviderTurnCapture.resolveTurns} can drop back to reconstruction
     * when the payload was captured under a different wire shape — the
     * alternative, deciding that at transform time, would put the same check in
     * two places. Not a fifth carrier: it WRAPS one of the others, which stays
     * the projection consumers read.
     */
    record Turn(String shape, String wire, Msg fallback) implements Msg {}
}
