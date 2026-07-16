package com.aktagon.llmkit;

/**
 * Wraps a pre-phase middleware veto cause (ADR-001) so callers can catch it
 * distinctly from a transport or provider error via {@link #getCause()}.
 */
public final class MiddlewareVetoException extends LlmKitException {
    MiddlewareVetoException(RuntimeException cause) {
        super("middleware veto: " + cause.getMessage(), cause);
    }
}
