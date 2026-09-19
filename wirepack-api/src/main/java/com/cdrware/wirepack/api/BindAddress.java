package com.cdrware.wirepack.api;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Local bind address for listen().
 */
public record BindAddress(String host, int port) {
    public BindAddress {
        Objects.requireNonNull(host, "host");
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    public InetSocketAddress toInetSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    public static BindAddress parse(String hostPort) {
        Objects.requireNonNull(hostPort, "hostPort");
        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0 || colon == hostPort.length() - 1) {
            throw new IllegalArgumentException("expected host:port, got: " + hostPort);
        }
        String host = hostPort.substring(0, colon);
        int port = Integer.parseInt(hostPort.substring(colon + 1));
        return new BindAddress(host, port);
    }

    public static BindAddress any(int port) {
        return new BindAddress("0.0.0.0", port);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
