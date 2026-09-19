package com.cdrware.wirepack.api;

/**
 * Library entry: {@code Wirepack.open(config)}.
 */
public interface Wirepack {
    static WirepackRuntime open(WirepackConfig config) {
        return WirepackBootstrap.open(config);
    }
}
