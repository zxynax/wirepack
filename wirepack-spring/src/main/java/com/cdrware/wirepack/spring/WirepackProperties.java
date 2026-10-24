package com.cdrware.wirepack.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code wirepack.*} settings for the optional UDP listener beside Tomcat.
 *
 * <p>{@code enabled} defaults to {@code false} so adding the jar does not bind a port.
 * Prefer {@code WIREPACK_SHARED_KEY_HEX} / config files over committing key material.
 */
@ConfigurationProperties(prefix = "wirepack")
public class WirepackProperties {
    private boolean enabled = false;
    private String bindHost = "0.0.0.0";
    private int bindPort = 9700;
    private int keyId = 1;
    /** Hex-encoded PSK (16+ bytes). Empty = cleartext CRC profile. */
    private String sharedKeyHex = "";
    /** Alternative to {@link #sharedKeyHex}. */
    private String sharedKeyBase64 = "";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBindHost() {
        return bindHost;
    }

    public void setBindHost(String bindHost) {
        this.bindHost = bindHost;
    }

    public int getBindPort() {
        return bindPort;
    }

    public void setBindPort(int bindPort) {
        this.bindPort = bindPort;
    }

    public int getKeyId() {
        return keyId;
    }

    public void setKeyId(int keyId) {
        this.keyId = keyId;
    }

    public String getSharedKeyHex() {
        return sharedKeyHex;
    }

    public void setSharedKeyHex(String sharedKeyHex) {
        this.sharedKeyHex = sharedKeyHex;
    }

    public String getSharedKeyBase64() {
        return sharedKeyBase64;
    }

    public void setSharedKeyBase64(String sharedKeyBase64) {
        this.sharedKeyBase64 = sharedKeyBase64;
    }

    /** Decoded PSK, or empty array when unset (cleartext). */
    public byte[] sharedKeyBytes() {
        String hex = sharedKeyHex == null ? "" : sharedKeyHex.trim();
        if (!hex.isEmpty()) {
            return decodeHex(hex);
        }
        String b64 = sharedKeyBase64 == null ? "" : sharedKeyBase64.trim();
        if (!b64.isEmpty()) {
            return java.util.Base64.getDecoder().decode(b64);
        }
        return new byte[0];
    }

    static byte[] decodeHex(String hex) {
        String s = hex.startsWith("0x") || hex.startsWith("0X") ? hex.substring(2) : hex;
        if ((s.length() & 1) != 0) {
            throw new IllegalArgumentException("wirepack.shared-key-hex must have even length");
        }
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            int hi = Character.digit(s.charAt(i * 2), 16);
            int lo = Character.digit(s.charAt(i * 2 + 1), 16);
            if (hi < 0 || lo < 0) {
                throw new IllegalArgumentException("wirepack.shared-key-hex is not hex");
            }
            out[i] = (byte) ((hi << 4) + lo);
        }
        return out;
    }
}
