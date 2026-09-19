package com.cdrware.wirepack.api;

import java.util.ServiceLoader;
import java.util.function.Function;

/**
 * Bridges api → core without a hard dependency: core registers the opener via ServiceLoader
 * or {@link #install(Function)}.
 */
public final class WirepackBootstrap {
    private static volatile Function<WirepackConfig, WirepackRuntime> opener;

    private WirepackBootstrap() {}

    public static synchronized void install(Function<WirepackConfig, WirepackRuntime> factory) {
        opener = factory;
    }

    static WirepackRuntime open(WirepackConfig config) {
        Function<WirepackConfig, WirepackRuntime> f = opener;
        if (f == null) {
            ServiceLoader<WirepackOpener> loader = ServiceLoader.load(WirepackOpener.class);
            for (WirepackOpener o : loader) {
                install(o::open);
                f = opener;
                break;
            }
        }
        if (f == null) {
            throw new IllegalStateException(
                    "No WirepackRuntime implementation found. Add wirepack-core (and transport) to the classpath.");
        }
        return f.apply(config);
    }

    @FunctionalInterface
    public interface WirepackOpener {
        WirepackRuntime open(WirepackConfig config);
    }
}
