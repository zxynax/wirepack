package com.cdrware.wirepack.spring;

import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.WirepackException;
import com.cdrware.wirepack.codec.KvTreeCodec;
import com.cdrware.wirepack.codec.ObjectBinder;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.converter.HttpMessageNotWritableException;

import java.io.IOException;
import java.util.Map;

/**
 * HTTP body codec for {@code Content-Type: application/wirepack}.
 *
 * <p>The body is one {@code KV_TREE} value: the same object tree {@link ObjectBinder}
 * uses for a UDP RPC payload. It is not a frame and it does not carry the RPC envelope.
 * The HTTP path is the route.
 */
public class WirepackHttpMessageConverter extends AbstractHttpMessageConverter<Object> {
    public static final MediaType MEDIA_TYPE = new MediaType("application", "wirepack");

    private final SafetyLimits limits;

    public WirepackHttpMessageConverter() {
        this(SafetyLimits.defaults());
    }

    public WirepackHttpMessageConverter(SafetyLimits limits) {
        super(MEDIA_TYPE);
        this.limits = limits == null ? SafetyLimits.defaults() : limits;
        setDefaultCharset(null);
    }

    public SafetyLimits limits() {
        return limits;
    }

    @Override
    public boolean canRead(Class<?> clazz, MediaType mediaType) {
        return supports(clazz) && isWirepack(mediaType);
    }

    @Override
    public boolean canWrite(Class<?> clazz, MediaType mediaType) {
        // null media type means "advertise supported types" during content negotiation.
        // */* must not select this converter ahead of JSON.
        if (!supports(clazz)) {
            return false;
        }
        if (mediaType == null) {
            return true;
        }
        return isWirepack(mediaType);
    }

    @Override
    protected boolean supports(Class<?> clazz) {
        if (clazz == null || clazz.isPrimitive() || clazz.isArray()) {
            return false;
        }
        if (clazz == Object.class || Map.class.isAssignableFrom(clazz)) {
            return true;
        }
        if (clazz.isInterface() || clazz.isEnum()) {
            return false;
        }
        String name = clazz.getName();
        return !name.startsWith("java.")
                && !name.startsWith("javax.")
                && !name.startsWith("jakarta.")
                && !name.startsWith("org.springframework.");
    }

    @Override
    protected Object readInternal(Class<?> clazz, HttpInputMessage inputMessage)
            throws IOException, HttpMessageNotReadableException {
        int max = limits.maxKvBytes();
        byte[] raw = inputMessage.getBody().readNBytes(max + 1);
        if (raw.length > max) {
            throw new HttpMessageNotReadableException(
                    "application/wirepack body exceeds maxKvBytes " + max, inputMessage);
        }
        try {
            Map<String, Object> tree = KvTreeCodec.decode(raw, limits);
            if (clazz == Object.class || Map.class.isAssignableFrom(clazz)) {
                return tree;
            }
            return ObjectBinder.fromTree(tree, clazz);
        } catch (WirepackException e) {
            throw new HttpMessageNotReadableException(
                    "application/wirepack body is not a KV_TREE for " + clazz.getName(), e, inputMessage);
        }
    }

    @Override
    protected void writeInternal(Object object, HttpOutputMessage outputMessage)
            throws IOException, HttpMessageNotWritableException {
        try {
            Map<String, Object> tree = object == null ? Map.of() : ObjectBinder.toTree(object);
            byte[] raw = KvTreeCodec.encode(tree, limits);
            outputMessage.getHeaders().setContentType(MEDIA_TYPE);
            outputMessage.getHeaders().setContentLength(raw.length);
            outputMessage.getBody().write(raw);
        } catch (WirepackException e) {
            throw new HttpMessageNotWritableException("cannot encode application/wirepack body", e);
        }
    }

    static boolean isWirepack(MediaType mediaType) {
        return mediaType != null
                && "application".equalsIgnoreCase(mediaType.getType())
                && "wirepack".equalsIgnoreCase(mediaType.getSubtype());
    }
}
