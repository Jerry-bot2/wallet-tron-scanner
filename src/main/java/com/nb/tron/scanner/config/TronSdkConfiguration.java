package com.nb.tron.scanner.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nb.tron.scanner.support.TronSdkCalls;
import com.nb.tron.sdk.TronSdkClient;
import com.nb.tron.sdk.TronSdkOptions;
import com.nb.tron.sdk.block.TronBlockGateway;
import com.nb.tron.sdk.codec.TronAddressCodec;
import com.nb.tron.sdk.node.TronNodePool;
import com.nb.tron.sdk.node.TronNodePoolOptions;
import com.nb.tron.sdk.parser.TronBlockParser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

/**
 * 装配TRON SDK。
 *
 * <p>Scanner需要使用运行配置中的连接超时，因此通过SDK高级入口注入专用HttpClient；
 * 普通业务项目可以直接使用SDK默认入口。</p>
 * <p>
 * Author: bin jack
 * Date: 07.10.26
 */
@Configuration(proxyBeanMethods = false)
public class TronSdkConfiguration {

    @Bean
    public HttpClient tronHttpClient(TronScannerProperties properties) {
        return HttpClient.newBuilder().connectTimeout(properties.getNode().getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Bean
    public TronSdkClient tronSdkClient(@Qualifier("tronHttpClient") HttpClient httpClient,
                                       ObjectMapper objectMapper,
                                       TronScannerProperties properties) {
        TronNodeProperties nodeProperties = properties.getNode();
        TronNodePoolOptions options = new TronNodePoolOptions(
            properties.getExpectedGenesisBlockId(),
            nodeProperties.getFailureThreshold(),
            nodeProperties.getHeightLagThreshold(),
            nodeProperties.getRecoveryCooldown());
        TronSdkOptions sdkOptions = new TronSdkOptions(
            nodeProperties.getNodes().stream()
                .map(TronNodeEndpointProperties::toSdkDefinition)
                .toList(),
            options,
            nodeProperties.getReadTimeout(),
            nodeProperties.getMaxResponseSize().toBytes());
        return TronSdkCalls.execute(() -> new TronSdkClient(httpClient, objectMapper, sdkOptions));
    }

    @Bean
    public TronNodePool tronNodePool(TronSdkClient sdkClient) {
        return sdkClient.nodes();
    }

    @Bean
    public TronBlockGateway tronBlockGateway(TronSdkClient sdkClient) {
        return sdkClient.blocks();
    }

    @Bean
    public TronAddressCodec tronAddressCodec(TronSdkClient sdkClient) {
        return sdkClient.addresses();
    }

    @Bean
    public TronBlockParser tronBlockParser(TronSdkClient sdkClient) {
        return sdkClient.blockParser();
    }
}
