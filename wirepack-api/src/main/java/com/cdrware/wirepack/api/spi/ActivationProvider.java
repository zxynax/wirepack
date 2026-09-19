package com.cdrware.wirepack.api.spi;

/**
 * Pluggable out-of-band activation / key bootstrap.
 */
@FunctionalInterface
public interface ActivationProvider {
    ActivationResult activate(ActivationRequest request) throws ActivationException;
}
