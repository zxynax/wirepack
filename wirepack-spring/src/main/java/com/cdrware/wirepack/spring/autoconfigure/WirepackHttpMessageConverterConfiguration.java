package com.cdrware.wirepack.spring.autoconfigure;

import com.cdrware.wirepack.spring.WirepackHttpMessageConverter;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.http.converter.autoconfigure.ClientHttpMessageConvertersCustomizer;
import org.springframework.boot.http.converter.autoconfigure.ServerHttpMessageConvertersCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.http.converter.HttpMessageConverter;

import java.util.List;

/**
 * Registers {@link WirepackHttpMessageConverter} for servlet and client HTTP.
 *
 * <p>The converter is not a bean. Boot prepends {@link HttpMessageConverter} beans,
 * and a leading converter would answer a wildcard Accept. Appending leaves
 * {@code application/json} as that default. {@code wirepack.enabled} is not
 * required; that flag only opens the UDP listener.
 */
@AutoConfiguration
@ConditionalOnClass({HttpMessageConverter.class, ServerHttpMessageConvertersCustomizer.class})
public class WirepackHttpMessageConverterConfiguration {

    @Bean
    public ServerHttpMessageConvertersCustomizer wirepackServerHttpMessageConvertersCustomizer() {
        return builder -> builder.configureMessageConvertersList(
                converters -> appendWirepack(converters, new WirepackHttpMessageConverter()));
    }

    @Bean
    @ConditionalOnClass(ClientHttpMessageConvertersCustomizer.class)
    public ClientHttpMessageConvertersCustomizer wirepackClientHttpMessageConvertersCustomizer() {
        return builder -> builder.configureMessageConvertersList(
                converters -> appendWirepack(converters, new WirepackHttpMessageConverter()));
    }

    private static void appendWirepack(
            List<HttpMessageConverter<?>> converters,
            WirepackHttpMessageConverter converter
    ) {
        for (HttpMessageConverter<?> existing : converters) {
            if (existing instanceof WirepackHttpMessageConverter) {
                return;
            }
        }
        converters.add(converter);
    }
}
