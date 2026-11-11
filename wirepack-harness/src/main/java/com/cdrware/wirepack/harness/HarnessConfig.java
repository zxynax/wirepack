package com.cdrware.wirepack.harness;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flat {@code harness.yaml} plus command-line overrides.
 *
 * <p>The file is a documented subset: {@code key: value} lines, optional
 * quotes, and {@code #} comments. Nested YAML is not accepted.
 * A flag on the command line replaces the same key from the file.
 */
public final class HarnessConfig {
    private final String mode;
    private final String bind;
    private final String peer;
    private final boolean peerExplicit;
    private final String keyHex;
    private final int keyId;
    private final long holdMs;
    private final String activateUrl;
    private final String deviceId;
    private final String organization;
    private final String health;
    private final Integer activationServerPort;

    private HarnessConfig(
            String mode,
            String bind,
            String peer,
            boolean peerExplicit,
            String keyHex,
            int keyId,
            long holdMs,
            String activateUrl,
            String deviceId,
            String organization,
            String health,
            Integer activationServerPort
    ) {
        this.mode = mode;
        this.bind = bind;
        this.peer = peer;
        this.peerExplicit = peerExplicit;
        this.keyHex = keyHex;
        this.keyId = keyId;
        this.holdMs = holdMs;
        this.activateUrl = activateUrl;
        this.deviceId = deviceId;
        this.organization = organization;
        this.health = health;
        this.activationServerPort = activationServerPort;
    }

    public static HarnessConfig parse(String[] args) throws IOException {
        String configPath = null;
        for (int i = 0; i < args.length; i++) {
            if ("--config".equals(args[i])) {
                configPath = required(args, ++i, "--config");
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (configPath != null) {
            values.putAll(parseFlat(Files.readString(Path.of(configPath))));
        }
        boolean peerExplicit = values.containsKey("peer");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--config" -> i++;
                case "--mode" -> values.put("mode", required(args, ++i, "--mode"));
                case "--bind" -> values.put("bind", required(args, ++i, "--bind"));
                case "--peer" -> {
                    values.put("peer", required(args, ++i, "--peer"));
                    peerExplicit = true;
                }
                case "--key" -> values.put("key", required(args, ++i, "--key"));
                case "--key-id" -> values.put("keyId", required(args, ++i, "--key-id"));
                case "--hold-ms" -> values.put("holdMs", required(args, ++i, "--hold-ms"));
                case "--activate-url" -> values.put("activateUrl", required(args, ++i, "--activate-url"));
                case "--device-id" -> values.put("deviceId", required(args, ++i, "--device-id"));
                case "--organization" -> values.put("organization", required(args, ++i, "--organization"));
                case "--health" -> values.put("health", required(args, ++i, "--health"));
                case "--activation-server" -> values.put(
                        "activationServer", required(args, ++i, "--activation-server"));
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
        return from(values, peerExplicit);
    }

    static Map<String, String> parseFlat(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String raw : text.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("expected key: value, got: " + raw);
            }
            String key = line.substring(0, colon).trim();
            String value = unquote(stripComment(line.substring(colon + 1).trim()));
            if (key.isEmpty() || value.isEmpty()) {
                throw new IllegalArgumentException("expected key: value, got: " + raw);
            }
            values.put(key, value);
        }
        return values;
    }

    private static HarnessConfig from(Map<String, String> values, boolean peerExplicit) {
        String mode = values.getOrDefault("mode", "listen");
        Integer activationServer = null;
        if (values.containsKey("activationServer")) {
            activationServer = Integer.parseInt(values.get("activationServer"));
            if (activationServer < 0 || activationServer > 65535) {
                throw new IllegalArgumentException("activationServer port out of range");
            }
        }
        return new HarnessConfig(
                mode,
                values.getOrDefault("bind", "0.0.0.0:9700"),
                values.get("peer"),
                peerExplicit,
                values.get("key"),
                Integer.parseInt(values.getOrDefault("keyId", "0")),
                Long.parseLong(values.getOrDefault("holdMs", "5000")),
                values.get("activateUrl"),
                values.getOrDefault("deviceId", "harness"),
                values.getOrDefault("organization", "default"),
                values.get("health"),
                activationServer
        );
    }

    private static String stripComment(String value) {
        int hash = value.indexOf(" #");
        if (hash >= 0) {
            return value.substring(0, hash).trim();
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String required(String[] args, int index, String flag) {
        if (index >= args.length) {
            throw new IllegalArgumentException(flag + " needs a value");
        }
        return args[index];
    }

    public String mode() {
        return mode;
    }

    public String bind() {
        return bind;
    }

    public String peer() {
        return peer;
    }

    public boolean peerExplicit() {
        return peerExplicit;
    }

    public String keyHex() {
        return keyHex;
    }

    public int keyId() {
        return keyId;
    }

    public long holdMs() {
        return holdMs;
    }

    public String activateUrl() {
        return activateUrl;
    }

    public String deviceId() {
        return deviceId;
    }

    public String organization() {
        return organization;
    }

    public String health() {
        return health;
    }

    public Integer activationServerPort() {
        return activationServerPort;
    }
}
