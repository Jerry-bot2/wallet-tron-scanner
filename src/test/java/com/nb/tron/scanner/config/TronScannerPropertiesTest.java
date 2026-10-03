package com.nb.tron.scanner.config;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class TronScannerPropertiesTest {

    @Test
    void shouldAcceptValidNodeConfiguration() {
        TronScannerProperties properties = validProperties();

        assertThatNoException().isThrownBy(properties::validate);
    }

    @Test
    void shouldRejectDuplicateNodeCode() {
        TronScannerProperties properties = validProperties();
        properties.getNode().getNodes().get(1).setCode("primary");

        assertThatThrownBy(properties::validate)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
    }

    @Test
    void shouldRequireFullNodeAndSolidityNode() {
        TronScannerProperties properties = validProperties();
        properties.getNode().setNodes(List.of(
            node("full-primary", TronNodeRole.FULL_NODE, "http://127.0.0.1:8090")));

        assertThatThrownBy(properties::validate)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
    }

    private TronScannerProperties validProperties() {
        TronScannerProperties properties = new TronScannerProperties();
        properties.setExpectedGenesisBlockId("0".repeat(64));
        properties.setStartBlockHeight(1L);
        properties.getNode().setNodes(List.of(
            node("primary", TronNodeRole.FULL_NODE, "http://127.0.0.1:8090"),
            node("solidity", TronNodeRole.SOLIDITY_NODE, "http://127.0.0.1:8091")));
        return properties;
    }

    private TronNodeEndpointProperties node(String code, TronNodeRole role, String baseUrl) {
        TronNodeEndpointProperties endpoint = new TronNodeEndpointProperties();
        endpoint.setCode(code);
        endpoint.setRole(role);
        endpoint.setPriority(1);
        endpoint.setBaseUrl(URI.create(baseUrl));
        return endpoint;
    }
}
