// Code generated — DO NOT EDIT.

package com.aktagon.llmkit.providers.generated;

/**
 * ProviderTurn is one assistant turn exactly as the provider serialized it, captured at assistantTurnPath and replayed unchanged on every subsequent request in the run (ADR-085). It sits BESIDE the canonical projection, not instead of it: Message.role/.content/.tool_calls stay the consumer's view of the turn, and this is what goes back on the wire. Present only on a turn the SDK received from a provider; a caller-authored turn (History(...), a compaction hook) has none and is reconstructed as before.
 */
public record ProviderTurn(String wireShape, String wire) {}
