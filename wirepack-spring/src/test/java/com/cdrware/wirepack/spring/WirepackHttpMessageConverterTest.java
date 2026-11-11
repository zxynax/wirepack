package com.cdrware.wirepack.spring;

import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.codec.KvTreeCodec;
import com.cdrware.wirepack.codec.ObjectBinder;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import org.springframework.mock.http.MockHttpOutputMessage;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WirepackHttpMessageConverterTest {

    public record EchoIn(String msg) {}

    public record EchoOut(String msg) {}

    @Test
    void roundTripMatchesKvTreeBinder() throws Exception {
        WirepackHttpMessageConverter converter = new WirepackHttpMessageConverter();
        EchoIn in = new EchoIn("hi");
        byte[] expected = KvTreeCodec.encode(ObjectBinder.toTree(in), SafetyLimits.defaults());

        MockHttpOutputMessage written = new MockHttpOutputMessage();
        converter.write(in, WirepackHttpMessageConverter.MEDIA_TYPE, written);
        assertArrayEquals(expected, written.getBodyAsBytes());
        assertEquals(WirepackHttpMessageConverter.MEDIA_TYPE, written.getHeaders().getContentType());

        EchoIn back = (EchoIn) converter.read(EchoIn.class, new MockHttpInputMessage(expected));
        assertEquals("hi", back.msg());
    }

    @Test
    void onlyClaimsApplicationWirepack() {
        WirepackHttpMessageConverter converter = new WirepackHttpMessageConverter();
        assertTrue(converter.canRead(EchoIn.class, WirepackHttpMessageConverter.MEDIA_TYPE));
        assertTrue(converter.canWrite(EchoOut.class, WirepackHttpMessageConverter.MEDIA_TYPE));
        assertTrue(converter.canWrite(EchoOut.class, null));
        assertFalse(converter.canRead(EchoIn.class, MediaType.APPLICATION_JSON));
        assertFalse(converter.canWrite(EchoOut.class, MediaType.APPLICATION_JSON));
        assertFalse(converter.canWrite(EchoOut.class, MediaType.ALL));
        assertFalse(converter.canRead(String.class, WirepackHttpMessageConverter.MEDIA_TYPE));
        assertFalse(converter.canWrite(byte[].class, WirepackHttpMessageConverter.MEDIA_TYPE));
    }

    @Test
    void rejectsShortAndOversizedBodies() {
        WirepackHttpMessageConverter converter = new WirepackHttpMessageConverter();
        assertThrows(HttpMessageNotReadableException.class,
                () -> converter.read(EchoIn.class, new MockHttpInputMessage(new byte[] {0, 1})));

        byte[] huge = new byte[SafetyLimits.defaults().maxKvBytes() + 1];
        assertThrows(HttpMessageNotReadableException.class,
                () -> converter.read(Map.class, new MockHttpInputMessage(huge)));
    }

    @Test
    void nullBodyEncodesEmptyTree() throws Exception {
        WirepackHttpMessageConverter converter = new WirepackHttpMessageConverter();
        MockHttpOutputMessage written = new MockHttpOutputMessage();
        converter.write(null, WirepackHttpMessageConverter.MEDIA_TYPE, written);
        byte[] expected = KvTreeCodec.encode(Map.of(), SafetyLimits.defaults());
        assertArrayEquals(expected, written.getBodyAsBytes());
        assertEquals(WirepackHttpMessageConverter.MEDIA_TYPE, written.getHeaders().getContentType());
        assertNull(written.getHeaders().getContentType().getCharset());
    }
}
