package com.cdrware.wirepack.bench;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

record BenchArgs(
        Scenario scenario,
        TransportChoice transport,
        int count,
        int size,
        int batch,
        int runs,
        long timeoutMs,
        String reportPath,
        boolean crypto,
        byte[] sharedKey,
        double loss,
        long lossSeed,
        int sessions,
        boolean help
) {
    static final int DEFAULT_SMALL_COUNT = 5_000;
    static final int DEFAULT_SMALL_SIZE = 64;
    static final int DEFAULT_LARGE_COUNT = 20;
    static final int DEFAULT_LARGE_SIZE = 32 * 1024;
    static final int DEFAULT_MULTI_COUNT = 500;
    static final int DEFAULT_MULTI_SIZE = 64;
    static final int DEFAULT_BATCH = 64;
    static final int DEFAULT_SESSIONS = 8;
    static final String DEFAULT_REPORT = "wirepack-bench/target/bench-report.md";
    /** Fixed bench PSK (16 bytes) when --crypto is on and --key omitted. */
    static final String DEFAULT_KEY_HEX = "00112233445566778899aabbccddeeff";

    List<Scenario> scenarios() {
        if (scenario == Scenario.ALL) {
            return List.of(Scenario.SMALL_MANY, Scenario.LARGE_FEW, Scenario.MULTI_SESSION);
        }
        return List.of(scenario);
    }

    int countFor(Scenario s) {
        if (count > 0) {
            return count;
        }
        return switch (s) {
            case LARGE_FEW -> DEFAULT_LARGE_COUNT;
            case MULTI_SESSION -> DEFAULT_MULTI_COUNT;
            default -> DEFAULT_SMALL_COUNT;
        };
    }

    int sizeFor(Scenario s) {
        if (size > 0) {
            return size;
        }
        return switch (s) {
            case LARGE_FEW -> DEFAULT_LARGE_SIZE;
            case MULTI_SESSION -> DEFAULT_MULTI_SIZE;
            default -> DEFAULT_SMALL_SIZE;
        };
    }

    int sessionsFor(Scenario s) {
        if (s == Scenario.MULTI_SESSION) {
            return Math.max(1, sessions);
        }
        return 1;
    }

    static String usage() {
        return """
                Usage: wirepack-bench [options]

                  --scenario small-many|large-few|multi-session|all   (default: all)
                  --transport wirepack|rest|both        (default: both)
                  --count N      entities per conversation (default depends on scenario)
                  --size BYTES   payload bytes per entity
                  --batch N      send batch size (default: 64)
                  --sessions N   parallel conversations for multi-session (default: 8)
                  --runs N       average wall time over N runs (default: 1)
                  --timeout-ms N wait for delivery (default: 120000)
                  --crypto true|false   wirepack AEAD (default: true)
                  --key HEX      PSK for wirepack crypto (default: built-in 16-byte test key)
                  --loss P       DATA-frame drop probability 0..1 (wirepack only; default: 0)
                  --loss-seed N  RNG seed for loss (default: 1)
                  --report PATH  Markdown+CSV report path
                                 (default: wirepack-bench/target/bench-report.md)
                  --help

                small-many: many tiny entities (REST JSON batches).
                large-few:  large logical entities (wirepack ENTITY_FRAG; REST octet-stream).
                multi-session: N parallel conversations × count entities each.
                """;
    }

    static BenchArgs parse(String[] args) {
        Scenario scenario = Scenario.ALL;
        TransportChoice transport = TransportChoice.BOTH;
        int count = -1;
        int size = -1;
        int batch = DEFAULT_BATCH;
        int runs = 1;
        long timeoutMs = 120_000L;
        String reportPath = DEFAULT_REPORT;
        boolean crypto = true;
        String keyHex = DEFAULT_KEY_HEX;
        double loss = 0.0;
        long lossSeed = 1L;
        int sessions = DEFAULT_SESSIONS;
        boolean help = false;

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--scenario" -> scenario = Scenario.parse(args[++i]);
                case "--transport" -> transport = TransportChoice.parse(args[++i]);
                case "--count" -> count = Integer.parseInt(args[++i]);
                case "--size" -> size = Integer.parseInt(args[++i]);
                case "--batch" -> batch = Integer.parseInt(args[++i]);
                case "--sessions" -> sessions = Integer.parseInt(args[++i]);
                case "--runs" -> runs = Integer.parseInt(args[++i]);
                case "--timeout-ms" -> timeoutMs = Long.parseLong(args[++i]);
                case "--crypto" -> crypto = parseBool(args[++i]);
                case "--key" -> keyHex = args[++i];
                case "--loss" -> loss = Double.parseDouble(args[++i]);
                case "--loss-seed" -> lossSeed = Long.parseLong(args[++i]);
                case "--report" -> reportPath = args[++i];
                case "--help", "-h" -> help = true;
                default -> throw new IllegalArgumentException("unknown arg: " + a + "\n" + usage());
            }
        }
        if (batch < 1) {
            throw new IllegalArgumentException("--batch must be >= 1");
        }
        if (sessions < 1) {
            throw new IllegalArgumentException("--sessions must be >= 1");
        }
        if (loss < 0.0 || loss > 1.0) {
            throw new IllegalArgumentException("--loss must be in [0,1]");
        }
        byte[] key = crypto ? HexFormat.of().parseHex(keyHex) : null;
        if (crypto && (key == null || key.length < 16)) {
            throw new IllegalArgumentException("--key must be at least 16 bytes hex when crypto is on");
        }
        return new BenchArgs(
                scenario, transport, count, size, batch, runs, timeoutMs, reportPath,
                crypto, key, loss, lossSeed, sessions, help);
    }

    private static boolean parseBool(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on" -> true;
            case "false", "0", "no", "off" -> false;
            default -> throw new IllegalArgumentException("expected boolean: " + s);
        };
    }
}

enum Scenario {
    SMALL_MANY,
    LARGE_FEW,
    MULTI_SESSION,
    ALL;

    String label() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    static Scenario parse(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "small-many", "small" -> SMALL_MANY;
            case "large-few", "large" -> LARGE_FEW;
            case "multi-session", "multi" -> MULTI_SESSION;
            case "all" -> ALL;
            default -> throw new IllegalArgumentException("unknown scenario: " + s);
        };
    }
}

enum TransportChoice {
    WIREPACK,
    REST,
    BOTH;

    boolean wirepack() {
        return this == WIREPACK || this == BOTH;
    }

    boolean rest() {
        return this == REST || this == BOTH;
    }

    static TransportChoice parse(String s) {
        return switch (s.toLowerCase(Locale.ROOT)) {
            case "wirepack", "wp" -> WIREPACK;
            case "rest", "http" -> REST;
            case "both" -> BOTH;
            default -> throw new IllegalArgumentException("unknown transport: " + s);
        };
    }
}

record BenchResult(long elapsedNanos, int entityCount, long payloadBytes) {
    double elapsedMs() {
        return elapsedNanos / 1_000_000.0;
    }

    double entitiesPerSec() {
        double sec = elapsedNanos / 1_000_000_000.0;
        return sec <= 0 ? 0 : entityCount / sec;
    }

    double mebibytesPerSec() {
        double sec = elapsedNanos / 1_000_000_000.0;
        return sec <= 0 ? 0 : (payloadBytes / (1024.0 * 1024.0)) / sec;
    }
}

/** Shared payload + type id helpers for both transports. */
final class BenchPayloads {
    /** Application entity type for whole (non-fragment) bench items. */
    static final int TYPE_ID = 2000;

    private BenchPayloads() {
    }

    static byte[] payload(int index, int size) {
        if (size < 0 || size > 0xFFFF) {
            throw new IllegalArgumentException("entity size must be 0..65535 (wirepack u16 limit)");
        }
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (index + i);
        }
        if (size >= 4) {
            b[0] = (byte) (index >>> 24);
            b[1] = (byte) (index >>> 16);
            b[2] = (byte) (index >>> 8);
            b[3] = (byte) index;
        }
        return b;
    }

    static List<byte[]> all(int count, int size) {
        List<byte[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            out.add(payload(i, size));
        }
        return out;
    }
}
