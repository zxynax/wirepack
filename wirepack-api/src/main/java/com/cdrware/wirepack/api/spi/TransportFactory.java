package com.cdrware.wirepack.api.spi;

import com.cdrware.wirepack.api.BindAddress;
import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.WirepackConfig;

import java.io.Closeable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Optional thin transport factory SPI (default: Netty UDP).
 */
public interface TransportFactory {
    Transport create(WirepackConfig config);

    interface Transport extends Closeable {
        CompletableFuture<Session> listen(BindAddress bind, Consumer<Session> onAccept);

        CompletableFuture<Session> connect(PeerAddress peer);

        SafetyLimits limits();

        /** Bound address after successful listen(); null if not listening. */
        default BindAddress localAddress() {
            return null;
        }

        @Override
        void close();
    }
}
