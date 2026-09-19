package com.cdrware.wirepack.harness;

import com.cdrware.wirepack.api.BindAddress;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.SessionAcceptor;
import com.cdrware.wirepack.api.Wirepack;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackRuntime;
import com.cdrware.wirepack.netty.NettyUdpTransportFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Standalone peer: {@code --mode listen|connect --bind host:port --peer host:port --key hex}
 */
public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Args parsed = Args.parse(args);
        SafetyLimits limits = SafetyLimits.defaults();
        byte[] key = parsed.keyHex == null ? new byte[16] : HexFormat.of().parseHex(parsed.keyHex);

        WirepackConfig config = WirepackConfig.builder()
                .sharedKey(key)
                .keyId(parsed.keyId)
                .limits(limits)
                .transport(new NettyUdpTransportFactory())
                .build();

        try (WirepackRuntime runtime = Wirepack.open(config)) {
            if ("listen".equals(parsed.mode)) {
                BindAddress bind = BindAddress.parse(parsed.bind);
                CountDownLatch done = new CountDownLatch(1);
                SessionAcceptor acceptor = runtime.listen(bind).get(10, TimeUnit.SECONDS);
                acceptor.onAccept(session -> {
                    log.info("Accepted session {} from {}", session.sessionId(), session.peer());
                    session.onEntity(e -> log.info("recv typeId={} len={}", e.typeId(), e.length()));
                    session.onClose(done::countDown);
                });
                log.info("Listening on {} (ctrl-c to stop)", bind);
                Runtime.getRuntime().addShutdownHook(new Thread(done::countDown));
                done.await();
            } else if ("connect".equals(parsed.mode)) {
                PeerAddress peer = PeerAddress.parse(parsed.peer);
                Session session = runtime.connect(peer).get(10, TimeUnit.SECONDS);
                session.onEntity(e -> log.info("recv typeId={} len={}", e.typeId(), e.length()));
                Entity ping = new Entity(1024, "ping".getBytes(StandardCharsets.UTF_8));
                session.send(List.of(ping)).get(5, TimeUnit.SECONDS);
                log.info("Sent ping to {}", peer);
                Thread.sleep(parsed.holdMs);
                session.close();
            } else {
                throw new IllegalArgumentException("unknown mode: " + parsed.mode);
            }
        }
    }

    static final class Args {
        String mode = "listen";
        String bind = "0.0.0.0:9700";
        String peer = "127.0.0.1:9700";
        String keyHex;
        int keyId;
        long holdMs = 5_000;

        static Args parse(String[] args) {
            Args a = new Args();
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--mode" -> a.mode = args[++i];
                    case "--bind" -> a.bind = args[++i];
                    case "--peer" -> a.peer = args[++i];
                    case "--key" -> a.keyHex = args[++i];
                    case "--key-id" -> a.keyId = Integer.parseInt(args[++i]);
                    case "--hold-ms" -> a.holdMs = Long.parseLong(args[++i]);
                    case "--help", "-h" -> {
                        System.out.println("""
                                Usage: wirepack-harness --mode listen|connect \\
                                  --bind host:port [--peer host:port] [--key hex] [--key-id n]
                                """);
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
                }
            }
            return a;
        }
    }
}
