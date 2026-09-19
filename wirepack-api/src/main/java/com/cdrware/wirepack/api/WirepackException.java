package com.cdrware.wirepack.api;

/**
 * Codec / framing error (bounds, CRC, schema conflict).
 */
public class WirepackException extends RuntimeException {
    public WirepackException(String message) {
        super(message);
    }

    public WirepackException(String message, Throwable cause) {
        super(message, cause);
    }
}
