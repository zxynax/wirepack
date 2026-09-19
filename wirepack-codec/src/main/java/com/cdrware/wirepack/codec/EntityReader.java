package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads TLV entities from a DATA payload. Unknown type_ids are skipped (forward compatible).
 */
public final class EntityReader {
    private final ByteBuffer buf;
    private final SafetyLimits limits;
    private int entitiesRead;

    public EntityReader(byte[] payload, SafetyLimits limits) {
        this.buf = ByteBuffer.wrap(payload == null ? new byte[0] : payload).order(ByteOrder.BIG_ENDIAN);
        this.limits = limits == null ? SafetyLimits.defaults() : limits;
    }

    public EntityReader(byte[] payload) {
        this(payload, SafetyLimits.defaults());
    }

    public boolean hasRemaining() {
        return buf.remaining() > 0;
    }

    /**
     * Reads next entity. Returns empty if EOE consumed or no more data.
     * Unknown type_ids still return an Entity (caller may ignore); value is the raw bytes.
     */
    public Entity next() {
        if (!hasRemaining()) {
            return null;
        }
        if (entitiesRead >= limits.maxEntitiesPerFrame()) {
            throw new WirepackException("maxEntitiesPerFrame exceeded");
        }
        require(4, "entity header");
        int typeId = Short.toUnsignedInt(buf.getShort());
        int length = Short.toUnsignedInt(buf.getShort());
        if (length > limits.maxStringBytes()) {
            throw new WirepackException("entity length exceeds maxStringBytes: " + length);
        }
        require(length, "entity value");
        byte[] value = new byte[length];
        buf.get(value);
        entitiesRead++;
        return new Entity(typeId, value);
    }

    /**
     * Reads all entities until EOE or end of buffer. EOE itself is not included in the list.
     */
    public List<Entity> readAll() {
        List<Entity> out = new ArrayList<>();
        while (hasRemaining()) {
            Entity e = next();
            if (e == null) {
                break;
            }
            if (e.typeId() == 0) {
                break; // EOE
            }
            out.add(e);
        }
        return out;
    }

    /**
     * Like {@link #readAll()} but also returns unknown type_ids as entities (skip is for
     * apps that only care about known types — here we surface them).
     */
    public List<Entity> readAllIncludingUnknown() {
        return readAll();
    }

    private void require(int n, String what) {
        if (buf.remaining() < n) {
            throw new WirepackException("truncated " + what + ": need " + n + " have " + buf.remaining());
        }
    }
}
