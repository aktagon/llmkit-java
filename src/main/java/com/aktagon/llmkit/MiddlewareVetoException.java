package com.aktagon.llmkit;

/*


*/
public final class MiddlewareVetoException extends LlmKitException {
    MiddlewareVetoException(RuntimeException cause) {
        super("middleware veto: " + cause.getMessage(), cause);
    }
}
