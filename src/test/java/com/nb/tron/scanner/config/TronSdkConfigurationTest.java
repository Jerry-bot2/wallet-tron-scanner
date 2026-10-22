package com.nb.tron.scanner.config;

import com.nb.tron.scanner.biz.DepositDiscoveryService;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.sdk.TronSdkClient;
import com.nb.tron.sdk.block.TronBlockGateway;
import com.nb.tron.sdk.codec.TronAddressCodec;
import com.nb.tron.sdk.enums.TronNodeRole;
import com.nb.tron.sdk.node.TronNodePool;
import com.nb.tron.sdk.parser.TronBlockParser;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 验证Scanner接入SDK后，节点客户端及充值解析器仍能正确装配
 * <p>
 * Author: bin jack
 * Date: 07.10.26
 */
class TronSdkConfigurationTest {

    @Test
    void shouldWireSdkAndScannerComponents() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            .withBean(TronScannerProperties.class, this::properties)
            .withBean(TronAddressIndex.class, () -> mock(TronAddressIndex.class))
            .withBean(TronCurrencyIndex.class, TronCurrencyIndex::new)
            .withUserConfiguration(TronSdkConfiguration.class, DepositDiscoveryService.class)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(TronSdkClient.class);
                assertThat(context).hasSingleBean(TronNodePool.class);
                assertThat(context).hasSingleBean(TronBlockGateway.class);
                assertThat(context).hasSingleBean(TronAddressCodec.class);
                assertThat(context).hasSingleBean(TronBlockParser.class);
                assertThat(context).hasSingleBean(DepositDiscoveryService.class);
            });
    }

    private TronScannerProperties properties() {
        TronScannerProperties properties = new TronScannerProperties();
        properties.setStartBlockHeight(1000L);
        properties.setExpectedGenesisBlockId("genesis-id");
        TronNodeEndpointProperties fullNode = endpoint(
            "full-node", TronNodeRole.FULL_NODE, 1, "http://127.0.0.1:8090");
        TronNodeEndpointProperties solidityNode = endpoint(
            "solidity-node", TronNodeRole.SOLIDITY_NODE, 2, "http://127.0.0.1:8091");
        properties.getNode().setNodes(List.of(fullNode, solidityNode));
        return properties;
    }

    private TronNodeEndpointProperties endpoint(String code,
                                                TronNodeRole role,
                                                int priority,
                                                String baseUrl) {
        TronNodeEndpointProperties endpoint = new TronNodeEndpointProperties();
        endpoint.setCode(code);
        endpoint.setRole(role);
        endpoint.setPriority(priority);
        endpoint.setBaseUrl(URI.create(baseUrl));
        return endpoint;
    }
}
