package com.cdrware.wirepack.netty;

import com.cdrware.wirepack.api.BindAddress;
import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackException;
import com.cdrware.wirepack.api.spi.TransportFactory;
import com.cdrware.wirepack.codec.FrameCodec;
import com.cdrware.wirepack.core.ReliableSession;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Netty UDP transport: one event loop, datagram inbound → decode → session; outbound encode + send.
 * No per-session thread.
 */
public final class NettyUdpTransport implements TransportFactory.Transport {
    private static final Logger log = LoggerFactory.getLogger(NettyUdpTransport.class);

    private final WirepackConfig config;
    private final FrameCodec frameCodec;
    private final EventLoopGroup group;
    private final PacketLossSettings packetLoss;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger sessionIds = new AtomicInteger(1);
    private final Map<String, ReliableSession> sessionsByPeer = new ConcurrentHashMap<>();
    private final Map<Integer, ReliableSession> sessionsById = new ConcurrentHashMap<>();

    private volatile Channel channel;
    private volatile Consumer<Session> acceptHandler;
    private volatile BindAddress localBind;

    public NettyUdpTransport(WirepackConfig config) {
        this(config, PacketLossSettings.none());
    }

    public NettyUdpTransport(WirepackConfig config, PacketLossSettings packetLoss) {
        this.config = config;
        this.frameCodec = new FrameCodec(config.limits());
        this.group = new NioEventLoopGroup(1);
        this.packetLoss = packetLoss == null ? PacketLossSettings.none() : packetLoss;
        // Keepalive / proactive retransmit when reactive REQ cannot make progress.
        this.group.scheduleAtFixedRate(this::reliabilityTick, 25, 25, TimeUnit.MILLISECONDS);
    }

    private void reliabilityTick() {
        if (closed.get()) {
            return;
        }
        for (ReliableSession session : sessionsById.values()) {
            try {
                session.onReliabilityTick();
            } catch (Exception e) {
                log.trace("reliability tick: {}", e.toString());
            }
        }
    }

    @Override
    public SafetyLimits limits() {
        return config.limits();
    }

    @Override
    public CompletableFuture<Session> listen(BindAddress bind, Consumer<Session> onAccept) {
        ensureOpen();
        this.acceptHandler = onAccept;
        CompletableFuture<Session> ready = new CompletableFuture<>();
        Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_REUSEADDR, true)
                .handler(new ChannelInitializer<NioDatagramChannel>() {
                    @Override
                    protected void initChannel(NioDatagramChannel ch) {
                        ch.pipeline().addLast(new InboundHandler());
                    }
                });

        bootstrap.bind(bind.toInetSocketAddress()).addListener(f -> {
            if (!f.isSuccess()) {
                ready.completeExceptionally(f.cause());
                return;
            }
            channel = ((io.netty.channel.ChannelFuture) f).channel();
            InetSocketAddress local = (InetSocketAddress) channel.localAddress();
            localBind = new BindAddress(
                    local.getAddress().getHostAddress(),
                    local.getPort());
            log.info("Listening on {}", localBind);
            ready.complete(null);
        });
        return ready;
    }

    @Override
    public CompletableFuture<Session> connect(PeerAddress peer) {
        ensureOpen();
        CompletableFuture<Session> future = new CompletableFuture<>();
        Runnable start = () -> {
            try {
                if (channel != null && channel.isActive()) {
                    ReliableSession session = createSession(peer);
                    session.sendHello();
                    future.complete(session);
                    return;
                }
                Bootstrap bootstrap = new Bootstrap()
                        .group(group)
                        .channel(NioDatagramChannel.class)
                        .handler(new ChannelInitializer<NioDatagramChannel>() {
                            @Override
                            protected void initChannel(NioDatagramChannel ch) {
                                ch.pipeline().addLast(new InboundHandler());
                            }
                        });
                bootstrap.bind(0).addListener(f -> {
                    if (!f.isSuccess()) {
                        future.completeExceptionally(f.cause());
                        return;
                    }
                    try {
                        channel = ((io.netty.channel.ChannelFuture) f).channel();
                        ReliableSession session = createSession(peer);
                        session.sendHello();
                        future.complete(session);
                    } catch (Exception e) {
                        future.completeExceptionally(e);
                    }
                });
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        };
        if (group.isShuttingDown()) {
            future.completeExceptionally(new WirepackException("event loop shut down"));
        } else {
            group.execute(start);
        }
        return future;
    }

    private ReliableSession createSession(PeerAddress peer) {
        String key = peerKey(peer);
        return sessionsByPeer.computeIfAbsent(key, k -> {
            int id = sessionIds.getAndIncrement();
            ReliableSession session = new ReliableSession(id, peer, config);
            session.setSender(this::sendFrame);
            sessionsById.put(id, session);
            session.onClose(() -> {
                sessionsByPeer.remove(key, session);
                sessionsById.remove(id, session);
            });
            Consumer<Session> h = acceptHandler;
            if (h != null) {
                h.accept(session);
            }
            return session;
        });
    }

    private void sendFrame(PeerAddress peer, byte[] frame) {
        Channel ch = channel;
        if (ch == null || !ch.isActive()) {
            throw new WirepackException("UDP channel not active");
        }
        if (packetLoss.shouldDrop(frame)) {
            log.trace("injected outbound loss to {}", peer);
            return;
        }
        InetSocketAddress addr = peer.toInetSocketAddress();
        DatagramPacket packet = new DatagramPacket(Unpooled.wrappedBuffer(frame), addr);
        ch.writeAndFlush(packet);
    }

    private final class InboundHandler extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket msg) {
            byte[] data = new byte[msg.content().readableBytes()];
            msg.content().readBytes(data);
            if (packetLoss.shouldDrop(data)) {
                log.trace("injected inbound loss");
                return;
            }
            if (data.length > config.limits().maxFrameBytes()) {
                log.debug("drop oversized datagram {}", data.length);
                return;
            }
            FrameCodec.DecodedFrame decoded;
            try {
                decoded = frameCodec.decode(data);
            } catch (WirepackException e) {
                log.debug("drop bad frame: {}", e.getMessage());
                return;
            }
            InetSocketAddress sender = msg.sender();
            PeerAddress peer = new PeerAddress(sender.getAddress().getHostAddress(), sender.getPort());
            // Prefer peer address: each side uses its own sessionId; UDP demux is by peer.
            ReliableSession session = sessionsByPeer.get(peerKey(peer));
            if (session == null) {
                session = createSession(peer);
            }
            try {
                session.onFrame(decoded);
            } catch (WirepackException e) {
                log.debug("drop frame handling: {}", e.getMessage());
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("UDP handler error", cause);
        }
    }

    public BindAddress localBind() {
        return localBind;
    }

    @Override
    public BindAddress localAddress() {
        return localBind;
    }

    private static String peerKey(PeerAddress peer) {
        return peer.host() + ":" + peer.port();
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("transport closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (ReliableSession s : sessionsById.values()) {
            try {
                s.close();
            } catch (Exception ignored) {
                // ignore
            }
        }
        Channel ch = channel;
        if (ch != null) {
            ch.close().syncUninterruptibly();
        }
        group.shutdownGracefully();
    }
}
