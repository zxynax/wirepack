package com.cdrware.wirepack.api;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Reliable UDP session: send entities, receive callbacks, close.
 *
 * <p>Tree helpers ({@link #sendTree}/{@link #onTree}) encode free-form nested maps as
 * {@code KV_TREE}, auto-fragmenting large values via {@code ENTITY_FRAG}. Object helpers
 * bind POJOs/records through the same path.
 */
public interface Session extends AutoCloseable {
    int sessionId();

    PeerAddress peer();

    CompletableFuture<Void> send(List<Entity> entities);

    default CompletableFuture<Void> send(Entity entity) {
        return send(List.of(entity));
    }

    /**
     * Encode {@code root} as {@code KV_TREE} (fragmenting if needed) and send.
     */
    CompletableFuture<Void> sendTree(Map<String, ?> root);

    /**
     * Bind {@code bean} to a tree and {@link #sendTree(Map)}.
     */
    <T> CompletableFuture<Void> sendObject(T bean);

    void onEntity(Consumer<Entity> handler);

    /**
     * Send {@code REGISTER} and complete when the peer answers with {@code REGISTER_ACK}
     * for this type id and schema hash. A rejected ack fails the future: the peer kept
     * its stored schema and quarantined the type.
     */
    CompletableFuture<RegisterAck> register(RegisteredSchema schema);

    /**
     * Schema stored from an accepted {@code REGISTER}, if this session has one for {@code typeId}.
     */
    Optional<RegisteredSchema> registeredSchema(int typeId);

    /**
     * Invoked for each complete {@code KV_TREE} (whole entity or reassembled frags).
     * Wire {@code ENTITY_FRAG} pieces are not delivered here.
     */
    void onTree(Consumer<Map<String, Object>> handler);

    /**
     * Decode each complete tree into {@code type} and invoke {@code handler}.
     */
    <T> void onObject(Class<T> type, Consumer<T> handler);

    /**
     * Register a server handler for RPC {@code route}. Incoming request envelopes are
     * not delivered to {@link #onTree}/{@link #onObject}.
     */
    void onRpc(String route, RpcHandler handler);

    /**
     * Send an RPC request and complete with the response payload bound to {@code responseType}.
     * Default timeout is 30 seconds.
     */
    default <T> CompletableFuture<T> request(String route, Object body, Class<T> responseType) {
        return request(route, body, responseType, Duration.ofSeconds(30));
    }

    /**
     * Send an RPC request (route + POJO payload) and complete with the peer response.
     */
    <T> CompletableFuture<T> request(String route, Object body, Class<T> responseType, Duration timeout);

    void onClose(Runnable handler);

    boolean isOpen();

    @Override
    void close();
}
