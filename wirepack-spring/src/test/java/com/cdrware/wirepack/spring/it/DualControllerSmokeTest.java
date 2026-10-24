package com.cdrware.wirepack.spring.it;

import com.cdrware.wirepack.api.PeerAddress;
import com.cdrware.wirepack.api.RpcError;
import com.cdrware.wirepack.api.RpcException;
import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.Session;
import com.cdrware.wirepack.api.Wirepack;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackMapping;
import com.cdrware.wirepack.api.WirepackRuntime;
import com.cdrware.wirepack.netty.NettyUdpTransportFactory;
import com.cdrware.wirepack.spring.WirepackProperties;
import com.cdrware.wirepack.spring.WirepackUdpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(
        classes = DualControllerSmokeTest.App.class,
        properties = {
                "wirepack.enabled=true",
                "wirepack.bind-host=127.0.0.1",
                "wirepack.bind-port=0",
                "wirepack.shared-key-hex=00112233445566778899aabbccddeeff",
                "spring.main.banner-mode=off"
        }
)
@AutoConfigureMockMvc
class DualControllerSmokeTest {

    public record EchoIn(String msg) {}

    public record EchoOut(String msg) {}

    @SpringBootApplication
    @Import(EchoController.class)
    static class App {}

    @RestController
    static class EchoController {
        @PostMapping("/echo")
        @WirepackMapping("echo")
        public EchoOut echo(@RequestBody EchoIn in) {
            return new EchoOut(in.msg());
        }
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WirepackUdpServer udpServer;

    @Autowired
    WirepackProperties properties;

    @Test
    void sameMethodViaHttpAndUdp() throws Exception {
        mockMvc.perform(post("/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"msg\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value("hi"));

        byte[] key = properties.sharedKeyBytes();
        WirepackConfig cfg = WirepackConfig.builder()
                .sharedKey(key)
                .keyId(properties.getKeyId())
                .limits(SafetyLimits.defaults())
                .transport(new NettyUdpTransportFactory())
                .build();
        try (WirepackRuntime clientRt = Wirepack.open(cfg)) {
            Session client = clientRt.connect(new PeerAddress(
                            "127.0.0.1", udpServer.bindAddress().port()))
                    .get(5, TimeUnit.SECONDS);
            EchoOut out = client.request("echo", new EchoIn("hi"), EchoOut.class)
                    .get(5, TimeUnit.SECONDS);
            assertEquals("hi", out.msg());

            ExecutionException ex = assertThrows(ExecutionException.class,
                    () -> client.request("nope", new EchoIn("x"), EchoOut.class)
                            .get(5, TimeUnit.SECONDS));
            RpcException rpc = assertInstanceOf(RpcException.class, ex.getCause());
            assertEquals(RpcError.NOT_FOUND, rpc.code());
            assertTrue(rpc.getMessage().contains("nope"));
        }
    }
}
