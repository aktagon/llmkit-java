package com.aktagon.llmkit;

import com.aktagon.llmkit.providers.generated.ToolCall;
import com.aktagon.llmkit.providers.generated.ToolResult;
import java.util.List;

/**
 * The internal message representation: a sum that is <em>exactly one of</em>
 * text, tool-calls, or tool-result (ADR-026 PIPE-007). The public
 * {@code Message} is a flat product that can encode an illegal multi-carrier
 * combination; this sealed interface cannot, so the transforms dispatch with
 * an exhaustive switch. Mirrors Swift's {@code Transforms.Msg} enum.
 */
sealed interface Msg {
    record Text(String role, String text) implements Msg {}

    record Calls(List<ToolCall> calls) implements Msg {}

    record ToolOutput(ToolResult result) implements Msg {}
}
