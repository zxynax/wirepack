package com.cdrware.wirepack.api;

import java.util.Map;

/**
 * Server-side handler for one RPC {@code route}. Return a POJO, a tree {@link Map}, or {@code null}.
 */
@FunctionalInterface
public interface RpcHandler {
    Object handle(Map<String, Object> payload) throws Exception;
}
