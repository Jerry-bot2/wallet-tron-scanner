package com.nb.tron.scanner.node;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.client.tron.TronNodeClient;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronNodeHealthStatus;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.model.TronNodeRuntimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class TronNodeHealthServiceTest {

    private static final String GENESIS_BLOCK_ID = "genesis-block-id";

    private TronScannerProperties scannerProperties;

    private TronNodeClient nodeClient;

    private TronNodeHealthService healthService;

    @BeforeEach
    void setUp() {
        scannerProperties = new TronScannerProperties();
        scannerProperties.setExpectedGenesisBlockId(GENESIS_BLOCK_ID);
        nodeClient = mock(TronNodeClient.class);
        healthService = new TronNodeHealthService(scannerProperties, nodeClient);
    }

    @Test
    void shouldRecordLatestStateForEveryHealthyNode() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE);
        TronNodeEndpointProperties solidityNode = endpoint("solidity-primary", TronNodeRole.SOLIDITY_NODE);
        scannerProperties.getNode().setNodes(List.of(fullNode, solidityNode));
        mockHealthyNode(fullNode, 100L);
        mockHealthyNode(solidityNode, 90L);

        List<TronNodeRuntimeState> states = healthService.refreshNodeStates();

        assertThat(states).hasSize(2).allSatisfy(state -> {
            assertThat(state.healthStatus()).isEqualTo(TronNodeHealthStatus.HEALTHY);
            assertThat(state.consecutiveFailureCount()).isZero();
            assertThat(state.lastSuccessAt()).isNotNull();
            assertThat(state.responseTimeMillis()).isNotNegative();
        });
        assertThat(healthService.findNodeState("full-primary"))
            .get()
            .extracting(TronNodeRuntimeState::latestBlockHeight)
            .isEqualTo(100L);
    }

    @Test
    void shouldMarkNodeUnhealthyAfterConsecutiveFailuresReachThreshold() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE);
        scannerProperties.getNode().setNodes(List.of(fullNode));
        mockGenesisBlock(fullNode);
        when(nodeClient.getHeadHeight(fullNode))
            .thenReturn(height(fullNode, 100L, "block-100"))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT));

        healthService.refreshNodeStates();
        healthService.refreshNodeStates();
        healthService.refreshNodeStates();
        healthService.refreshNodeStates();

        TronNodeRuntimeState state = healthService.findNodeState("full-primary").orElseThrow();
        assertThat(state.healthStatus()).isEqualTo(TronNodeHealthStatus.UNHEALTHY);
        assertThat(state.consecutiveFailureCount()).isEqualTo(3);
        assertThat(state.latestBlockHeight()).isEqualTo(100L);
        assertThat(state.lastSuccessAt()).isNotNull();
        assertThat(state.healthySince()).isNull();
    }

    @Test
    void shouldContinueCheckingOtherNodesWhenOneNodeFails() {
        TronNodeEndpointProperties failedNode = endpoint("full-primary", TronNodeRole.FULL_NODE);
        TronNodeEndpointProperties healthyNode = endpoint("full-backup", TronNodeRole.FULL_NODE);
        scannerProperties.getNode().setNodes(List.of(failedNode, healthyNode));
        when(nodeClient.getBlockHeaderByHeight(failedNode, 0L))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_CONNECT_FAILED));
        mockHealthyNode(healthyNode, 100L);

        List<TronNodeRuntimeState> states = healthService.refreshNodeStates();

        assertThat(states).extracting(TronNodeRuntimeState::healthStatus)
            .containsExactly(TronNodeHealthStatus.UNKNOWN, TronNodeHealthStatus.HEALTHY);
    }

    @Test
    void shouldRejectNodeFromDifferentNetworkBeforeReadingHeight() {
        TronNodeEndpointProperties wrongNetworkNode = endpoint(
            "full-wrong-network",
            TronNodeRole.FULL_NODE);
        scannerProperties.getNode().setNodes(List.of(wrongNetworkNode));
        when(nodeClient.getBlockHeaderByHeight(wrongNetworkNode, 0L))
            .thenReturn(height(wrongNetworkNode, 0L, "other-genesis-block-id"));

        List<TronNodeRuntimeState> states = healthService.refreshNodeStates();

        assertThat(states).singleElement().satisfies(state -> {
            assertThat(state.healthStatus()).isEqualTo(TronNodeHealthStatus.UNKNOWN);
            assertThat(state.latestBlockHeight()).isNull();
        });
        verify(nodeClient, never()).getHeadHeight(wrongNetworkNode);
    }

    private void mockHealthyNode(TronNodeEndpointProperties endpoint, long blockHeight) {
        mockGenesisBlock(endpoint);
        TronNodeHeight latestBlock = height(endpoint, blockHeight, "block-" + blockHeight);
        if (endpoint.getRole() == TronNodeRole.FULL_NODE) {
            when(nodeClient.getHeadHeight(endpoint)).thenReturn(latestBlock);
        } else {
            when(nodeClient.getSolidHeight(endpoint)).thenReturn(latestBlock);
        }
    }

    private void mockGenesisBlock(TronNodeEndpointProperties endpoint) {
        when(nodeClient.getBlockHeaderByHeight(endpoint, 0L))
            .thenReturn(height(endpoint, 0L, GENESIS_BLOCK_ID));
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
