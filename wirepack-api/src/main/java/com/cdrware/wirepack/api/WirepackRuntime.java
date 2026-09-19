package com.cdrware.wirepack.api;

import java.util.concurrent.CompletableFuture;

/**
 * Open runtime: connect peers or listen for sessions.
 */
public interface WirepackRuntime extends AutoCloseable {
    WirepackConfig config();

    CompletableFuture<Session> connect(PeerAddress peer);

    CompletableFuture<SessionAcceptor> listen(BindAddress bind);

    @Override
    void close();
}
