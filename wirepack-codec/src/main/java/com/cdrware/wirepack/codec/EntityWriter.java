package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.BuiltinTypeId;
import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes TLV entities into a DATA payload, packing until MTU / maxPayload.
 */
public final class EntityWriter {
    private final SafetyLimits limits;
    private final List<Entity> entities = new ArrayList<>();
    private int size;
    private boolean closed;

    public EntityWriter(SafetyLimits limits) {
        this.limits = limits == null ? SafetyLimits.defaults() : limits;
    }

    public EntityWriter() {
        this(SafetyLimits.defaults());
    }

    public int size() {
        return size;
    }

    public int count() {
        return entities.size();
    }

    public boolean tryAdd(Entity entity) {
        if (closed) {
            throw new WirepackException("writer already finished");
        }
        if (entities.size() >= limits.maxEntitiesPerFrame()) {
            return false;
        }
        int need = 4 + entity.length();
        if (size + need > limits.maxPayloadBytes()) {
            return false;
        }
        entities.add(entity);
        size += need;
        return true;
    }

    public void add(Entity entity) {
        if (!tryAdd(entity)) {
            throw new WirepackException("entity does not fit in payload budget");
        }
    }

    public void finishWithEoe() {
        if (closed) {
            return;
        }
        Entity eoe = Entity.eoe();
        if (!tryAdd(eoe)) {
            // EOE optional if no room
            closed = true;
            return;
        }
        closed = true;
    }

    public byte[] toByteArray() {
        ByteBuffer buf = ByteBuffer.allocate(size).order(ByteOrder.BIG_ENDIAN);
        for (Entity e : entities) {
            buf.putShort((short) e.typeId());
            buf.putShort((short) e.length());
            buf.put(e.valueRaw());
        }
        return buf.array();
    }

    public List<Entity> entities() {
        return List.copyOf(entities);
    }

    public static byte[] pack(SafetyLimits limits, List<Entity> entities, boolean appendEoe) {
        EntityWriter w = new EntityWriter(limits);
        for (Entity e : entities) {
            w.add(e);
        }
        if (appendEoe) {
            w.finishWithEoe();
        }
        return w.toByteArray();
    }

    public static byte[] encodeSingle(Entity entity) {
        ByteBuffer buf = ByteBuffer.allocate(4 + entity.length()).order(ByteOrder.BIG_ENDIAN);
        buf.putShort((short) entity.typeId());
        buf.putShort((short) entity.length());
        buf.put(entity.valueRaw());
        return buf.array();
    }

    public static Entity registerEntity(byte[] registerValue) {
        return new Entity(BuiltinTypeId.REGISTER.id(), registerValue);
    }
}
