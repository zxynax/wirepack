package com.cdrware.wirepack.codec;

import com.cdrware.wirepack.api.Entity;
import com.cdrware.wirepack.api.RegisteredSchema;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central type registry: register schemas, hash-check conflicts, encode/decode app entities.
 */
public final class TypeRegistry {
    private final ConcurrentHashMap<Integer, RegisteredSchema> byId = new ConcurrentHashMap<>();
    private final SafetyLimits limits;

    public TypeRegistry(SafetyLimits limits) {
        this.limits = limits == null ? SafetyLimits.defaults() : limits;
    }

    public TypeRegistry() {
        this(SafetyLimits.defaults());
    }

    public SafetyLimits limits() {
        return limits;
    }

    public void register(RegisteredSchema schema) {
        byId.compute(schema.typeId(), (id, existing) -> {
            if (existing != null && existing.schemaHash() != schema.schemaHash()) {
                throw new WirepackException("schema_hash conflict for type_id=" + id
                        + " existing=0x" + Integer.toHexString(existing.schemaHash())
                        + " new=0x" + Integer.toHexString(schema.schemaHash()));
            }
            return schema;
        });
    }

    public void registerFromEntity(Entity registerEntity) {
        if (registerEntity.typeId() != 1) {
            throw new WirepackException("expected REGISTER entity");
        }
        RegisteredSchema schema = RegisterCodec.decodeRegister(registerEntity.valueRaw(), limits);
        register(schema);
    }

    public Optional<RegisteredSchema> get(int typeId) {
        return Optional.ofNullable(byId.get(typeId));
    }

    public RegisteredSchema require(int typeId) {
        RegisteredSchema s = byId.get(typeId);
        if (s == null) {
            throw new WirepackException("unknown type_id: " + typeId);
        }
        return s;
    }

    public Entity encodeEntity(int typeId, Map<String, Object> fields) {
        RegisteredSchema schema = require(typeId);
        byte[] value = RegisterCodec.encodeFields(schema, fields, limits);
        return new Entity(typeId, value);
    }

    public Map<String, Object> decodeEntity(Entity entity) {
        RegisteredSchema schema = require(entity.typeId());
        return RegisterCodec.decodeFields(schema, entity.valueRaw(), limits);
    }

    public int size() {
        return byId.size();
    }
}
