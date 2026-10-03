package com.nb.tron.scanner.node;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.client.tron.TronNodeClient;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronNodeHeight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class TronNodeStartupValidatorTest {

    private static final String GENESIS_BLOCK_ID = "genesis-block-id";

    private TronScannerProperties scannerProperties;

    private TronNodeClient nodeClient;

    private TronNodeStartupValidator startupValidator;

    @BeforeEach
    void setUp() {
        scannerProperties = new TronScannerProperties();
        scannerProperties.setExpectedGenesisBlockId(GENESIS_BLOCK_ID);
        nodeClient = mock(TronNodeClient.class);
        startupValidator = new TronNodeStartupValidator(scannerProperties, nodeClient);
    }

    @Test
    void shouldAcceptAvailableFullNodeAndSolidityNode() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE);
        TronNodeEndpointProperties solidityNode = endpoint("solidity-primary", TronNodeRole.SOLIDITY_NODE);
        scannerProperties.getNode().setNodes(List.of(fullNode, solidityNode));
        mockAvailableNode(fullNode, 100L);
        mockAvailableNode(solidityNode, 90L);

        assertThatNoException().isThrownBy(startupValidator::validateConfiguredNodes);
    }

    @Test
    void shouldRejectNodeConnectedToWrongNetwork() {
        TronNodeEndpointProperties wrongFullNode = endpoint("full-wrong", TronNodeRole.FULL_NODE);
        TronNodeEndpointProperties validFullNode = endpoint("full-primary", TronNodeRole.FULL_NODE);
        scannerProperties.getNode().setNodes(List.of(wrongFullNode, validFullNode));
        when(nodeClient.getBlockHeaderByHeight(wrongFullNode, 0L))
            .thenReturn(height(wrongFullNode, 0L, "another-genesis"));
        mockAvailableNode(validFullNode, 100L);

        assertThatNoException().isThrownBy(startupValidator::validateConfiguredNodes);

        verify(nodeClient, never()).getHeadHeight(wrongFullNode);
    }

    @Test
    void shouldFailStartupWhenNoFullNodeIsAvailable() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE);
        scannerProperties.getNode().setNodes(List.of(fullNode));
        when(nodeClient.getBlockHeaderByHeight(fullNode, 0L))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_CONNECT_FAILED));

        assertThatThrownBy(startupValidator::validateConfiguredNodes)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_UNAVAILABLE);
    }

    @Test
    void shouldAllowStartupWhenOnlySolidityNodeIsUnavailable() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE);
        TronNodeEndpointProperties solidityNode = endpoint("solidity-primary", TronNodeRole.SOLIDITY_NODE);
        scannerProperties.getNode().setNodes(List.of(fullNode, solidityNode));
        mockAvailableNode(fullNode, 100L);
        when(nodeClient.getBlockHeaderByHeight(solidityNode, 0L))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT));

        assertThatNoException().isThrownBy(startupValidator::validateConfiguredNodes);
    }

    private void mockAvailableNode(TronNodeEndpointProperties endpoint, long latestHeight) {
        when(nodeClient.getBlockHeaderByHeight(endpoint, 0L))
            .thenReturn(height(endpoint, 0L, GENESIS_BLOCK_ID));
        TronNodeHeight latestBlock = height(endpoint, latestHeight, "block-" + latestHeight);
        if (endpoint.getRole() == TronNodeRole.FULL_NODE) {
            when(nodeClient.getHeadHeight(endpoint)).thenReturn(latestBlock);
        } else {
            when(nodeClient.getSolidHeight(endpoint)).thenReturn(latestBlock);
        }
    }

    private TronNodeEndpointProperties endpoint(String code, TronNodeRole role) {
        TronNodeEndpointProperties endpoint = new TronNodeEndpointProperties();
        endpoint.setCode(code);
        endpoint.setRole(role);
        endpoint.setPriority(1);
        endpoint.setBaseUrl(URI.create("http://127.0.0.1:8090"));
        return endpoint;
    }

    private TronNodeHeight height(TronNodeEndpointProperties endpoint,
                                  long blockHeight,
                                  String blockId) {
        return new TronNodeHeight(
            endpoint.getCode(),
            blockHeight,
            blockId,
            Instant.ofEpochMilli(1720000000000L));
    }
}
