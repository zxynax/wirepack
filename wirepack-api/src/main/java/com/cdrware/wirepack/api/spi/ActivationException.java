package com.cdrware.wirepack.api.spi;

/**
 * Activation / enrollment failure.
 */
public class ActivationException extends Exception {
    public ActivationException(String message) {
        super(message);
    }

    public ActivationException(String message, Throwable cause) {
        super(message, cause);
    }
}
