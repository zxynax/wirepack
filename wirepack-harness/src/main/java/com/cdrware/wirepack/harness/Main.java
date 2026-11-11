package com.cdrware.wirepack.harness;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Standalone peer. See {@code harness.yaml} for the service-shaped config.
 */
public final class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    static final String USAGE = """
            Usage: wirepack-harness [--config harness.yaml] \\
              --mode listen|connect|activate-then-connect \\
              [--bind host:port] [--peer host:port] [--key hex] [--key-id n] \\
              [--activate-url https://host/activate] [--device-id id] [--organization name] \\
              [--activation-server port] [--health host:port] [--hold-ms n]

            activate-then-connect calls the activation URL, then opens the data-plane
            session with the returned key and endpoint. --peer replaces that endpoint.
            --activation-server starts the local HTTP bootstrap (not production TLS).
            --health serves GET /health and GET /ready on a local port.
            """;

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            if ("--help".equals(arg) || "-h".equals(arg)) {
                System.out.println(USAGE);
                return;
            }
        }
        HarnessConfig config = HarnessConfig.parse(args);
        CountDownLatch stop = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(stop::countDown, "wirepack-harness-stop"));
        try (Harness harness = Harness.start(config)) {
            if ("listen".equals(config.mode())) {
                log.info("Running (ctrl-c to stop)");
                stop.await();
            } else if (!stop.await(config.holdMs(), TimeUnit.MILLISECONDS)) {
                log.info("Hold complete");
            }
            if (harness.boundAddress() != null) {
                log.info("Closing {}", harness.boundAddress());
            }
        }
    }
}
