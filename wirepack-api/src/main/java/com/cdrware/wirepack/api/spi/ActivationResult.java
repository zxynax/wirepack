package com.cdrware.wirepack.api.spi;

import com.cdrware.wirepack.api.PeerAddress;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Result of activation: key material, keyId, and data-plane endpoints.
 */
public record ActivationResult(
        int keyId,
        byte[] keyMaterial,
        List<PeerAddress> endpoints,
        Instant expiresAt
) {
    public ActivationResult {
        if (keyId < 0) {
            throw new IllegalArgumentException("keyId must be >= 0");
        }
        Objects.requireNonNull(keyMaterial, "keyMaterial");
        keyMaterial = keyMaterial.clone();
        endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
    }

    public byte[] keyMaterial() {
        return keyMaterial.clone();
    }

    /** Raw key without cloning — caller must not mutate. */
    public byte[] keyMaterialRaw() {
        return keyMaterial;
    }
}
