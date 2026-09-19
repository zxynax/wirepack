package com.cdrware.wirepack.netty;

import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.spi.TransportFactory;

/**
 * TransportFactory that creates {@link NettyUdpTransport}.
 */
public final class NettyUdpTransportFactory implements TransportFactory {
    private final PacketLossSettings packetLoss;

    public NettyUdpTransportFactory() {
        this(PacketLossSettings.none());
    }

    public NettyUdpTransportFactory(PacketLossSettings packetLoss) {
        this.packetLoss = packetLoss == null ? PacketLossSettings.none() : packetLoss;
    }

    public PacketLossSettings packetLoss() {
        return packetLoss;
    }

    @Override
    public Transport create(WirepackConfig config) {
        return new NettyUdpTransport(config, packetLoss);
    }
}
