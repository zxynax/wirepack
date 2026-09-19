package com.cdrware.wirepack.activate.http;

import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.spi.ActivationException;
import com.cdrware.wirepack.api.spi.ActivationProvider;
import com.cdrware.wirepack.api.spi.ActivationRequest;
import com.cdrware.wirepack.api.spi.ActivationResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTPS activate-device client using JDK HttpClient.
 * POST JSON → parse keyId / key / endpoints.
 */
public final class HttpsActivateDeviceProvider implements ActivationProvider {
    private static final Pattern KEY_ID = Pattern.compile("\"keyId\"\\s*:\\s*(\\d+)");
    private static final Pattern KEY_HEX = Pattern.compile("\"key\"\\s*:\\s*\"([0-9a-fA-F]+)\"");
    private static final Pattern KEY_B64 = Pattern.compile("\"keyBase64\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern ENDPOINT = Pattern.compile("\"host\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"port\"\\s*:\\s*(\\d+)");
    private static final Pattern ENDPOINT_STR = Pattern.compile("\"endpoints\"\\s*:\\s*\\[([^\\]]*)\\]");

    private final URI activateUri;
    private final HttpClient client;
    private final SafetyLimits limits;

    public HttpsActivateDeviceProvider(String activateUrl) {
        this(activateUrl, SafetyLimits.defaults());
    }

    public HttpsActivateDeviceProvider(String activateUrl, SafetyLimits limits) {
        this.activateUri = URI.create(Objects.requireNonNull(activateUrl, "activateUrl"));
        this.limits = limits == null ? SafetyLimits.defaults() : limits;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public ActivationResult activate(ActivationRequest request) throws ActivationException {
        Objects.requireNonNull(request, "request");
        String body = "{\"deviceId\":\"" + escape(request.deviceId())
                + "\",\"organization\":\"" + escape(request.organization()) + "\"}";
        if (body.getBytes(StandardCharsets.UTF_8).length > limits.maxActivateBodyBytes()) {
            throw new ActivationException("activate request body too large");
        }
        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(activateUri)
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = client.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new ActivationException("activate HTTP " + response.statusCode() + ": " + response.body());
            }
            String resp = response.body();
            if (resp.getBytes(StandardCharsets.UTF_8).length > limits.maxActivateBodyBytes()) {
                throw new ActivationException("activate response too large");
            }
            return parse(resp);
        } catch (ActivationException e) {
            throw e;
        } catch (Exception e) {
            throw new ActivationException("activate failed", e);
        }
    }

    static ActivationResult parse(String json) throws ActivationException {
        Matcher kid = KEY_ID.matcher(json);
        if (!kid.find()) {
            throw new ActivationException("missing keyId in activate response");
        }
        int keyId = Integer.parseInt(kid.group(1));

        byte[] key;
        Matcher kh = KEY_HEX.matcher(json);
        Matcher kb = KEY_B64.matcher(json);
        if (kh.find()) {
            key = HexFormat.of().parseHex(kh.group(1));
        } else if (kb.find()) {
            key = Base64.getDecoder().decode(kb.group(1));
        } else {
            throw new ActivationException("missing key / keyBase64 in activate response");
        }

        List<PeerAddress> endpoints = new ArrayList<>();
        Matcher em = ENDPOINT.matcher(json);
        while (em.find()) {
            endpoints.add(new PeerAddress(em.group(1), Integer.parseInt(em.group(2))));
        }
        if (endpoints.isEmpty()) {
            Matcher block = ENDPOINT_STR.matcher(json);
            if (block.find()) {
                Matcher str = Pattern.compile("\"([^\"]+:\\d+)\"").matcher(block.group(1));
                while (str.find()) {
                    endpoints.add(PeerAddress.parse(str.group(1)));
                }
            }
        }
        return new ActivationResult(keyId, key, endpoints, Instant.now().plus(Duration.ofDays(1)));
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
