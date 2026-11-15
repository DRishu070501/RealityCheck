package com.realitycheck.ai.provider;

/**
 * The provider did not produce a response. The message is safe to log: it never contains the
 * API key, the prompt or the evidence.
 */
public class AiProviderException extends RuntimeException {

    public enum Reason {
        TIMEOUT,
        PROVIDER_ERROR
    }

    private final Reason reason;

    public AiProviderException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public AiProviderException(Reason reason, String message) {
        this(reason, message, null);
    }

    public Reason reason() {
        return reason;
    }
}
