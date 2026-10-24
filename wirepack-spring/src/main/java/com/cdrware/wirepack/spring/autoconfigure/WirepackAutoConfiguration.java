package com.cdrware.wirepack.spring.autoconfigure;

import com.cdrware.wirepack.api.SafetyLimits;
import com.cdrware.wirepack.api.Wirepack;
import com.cdrware.wirepack.api.WirepackConfig;
import com.cdrware.wirepack.api.WirepackRuntime;
import com.cdrware.wirepack.netty.NettyUdpTransportFactory;
import com.cdrware.wirepack.spring.WirepackProperties;
import com.cdrware.wirepack.spring.WirepackUdpServer;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Optional Netty UDP listener next to Tomcat. Off unless {@code wirepack.enabled=true}.
 */
@AutoConfiguration
@ConditionalOnClass({WirepackRuntime.class, NettyUdpTransportFactory.class})
@ConditionalOnProperty(prefix = "wirepack", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(WirepackProperties.class)
public class WirepackAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public WirepackRuntime wirepackRuntime(WirepackProperties properties) {
        WirepackConfig.Builder b = WirepackConfig.builder()
                .keyId(properties.getKeyId())
                .limits(SafetyLimits.defaults())
                .transport(new NettyUdpTransportFactory());
        byte[] key = properties.sharedKeyBytes();
        if (key.length > 0) {
            b.sharedKey(key);
        }
        return Wirepack.open(b.build());
    }

    @Bean
    @ConditionalOnMissingBean
    public WirepackUdpServer wirepackUdpServer(
            WirepackRuntime runtime,
            WirepackProperties properties,
            ListableBeanFactory beanFactory
    ) {
        return new WirepackUdpServer(runtime, properties, beanFactory);
    }
}
