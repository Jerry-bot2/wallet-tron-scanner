package com.nb.tron.scanner.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nb.tron.sdk.client.TronNodeClient;
import com.nb.tron.sdk.codec.TronAddressCodec;
import com.nb.tron.sdk.parser.TronBlockParser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * 直接装配TRON SDK，复用应用JSON配置、HTTP连接与现有超时参数
 * <p>
 * Author: bin jack
 * Date: 07.10.26
 */
@Configuration(proxyBeanMethods = false)
public class TronHttpConfiguration {

    @Bean
    public TronAddressCodec tronAddressCodec() {
        return new TronAddressCodec();
    }

    @Bean
    public HttpClient tronHttpClient(TronScannerProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.getNode().getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    public TronNodeClient tronNodeClient(@Qualifier("tronHttpClient") HttpClient httpClient,
                                         ObjectMapper objectMapper, TronScannerProperties properties) {
        return new TronNodeClient(httpClient, objectMapper, properties.getNode().getReadTimeout(),
            properties.getNode().getMaxResponseSize().toBytes());
    }

    @Bean
    public TronBlockParser tronBlockParser(ObjectMapper objectMapper) {
        return new TronBlockParser(objectMapper);
    }
}
