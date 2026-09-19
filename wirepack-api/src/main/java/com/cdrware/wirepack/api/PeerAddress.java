package com.cdrware.wirepack.api;

import java.net.InetSocketAddress;
import java.util.Objects;

/**
 * Remote peer for connect().
 */
public record PeerAddress(String host, int port) {
    public PeerAddress {
        Objects.requireNonNull(host, "host");
        if (host.isBlank()) {
            throw new IllegalArgumentException("host must not be blank");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("port out of range: " + port);
        }
    }

    public InetSocketAddress toInetSocketAddress() {
        return new InetSocketAddress(host, port);
    }

    public static PeerAddress parse(String hostPort) {
        Objects.requireNonNull(hostPort, "hostPort");
        int colon = hostPort.lastIndexOf(':');
        if (colon <= 0 || colon == hostPort.length() - 1) {
            throw new IllegalArgumentException("expected host:port, got: " + hostPort);
        }
        String host = hostPort.substring(0, colon);
        int port = Integer.parseInt(hostPort.substring(colon + 1));
        return new PeerAddress(host, port);
    }

    @Override
    public String toString() {
        return host + ":" + port;
    }
}
