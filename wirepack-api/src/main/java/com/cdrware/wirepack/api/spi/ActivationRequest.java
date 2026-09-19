package com.cdrware.wirepack.api.spi;

import java.util.Map;
import java.util.Objects;

/**
 * Request to activate / enroll a device for key material.
 */
public record ActivationRequest(
        String deviceId,
        String organization,
        Map<String, String> attributes
) {
    public ActivationRequest {
        Objects.requireNonNull(deviceId, "deviceId");
        Objects.requireNonNull(organization, "organization");
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    public ActivationRequest(String deviceId, String organization) {
        this(deviceId, organization, Map.of());
    }
}
