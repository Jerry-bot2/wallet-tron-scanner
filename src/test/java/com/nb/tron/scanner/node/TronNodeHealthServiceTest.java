package com.nb.tron.scanner.node;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronNodeHealthStatus;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronNodeRuntimeState;
import com.nb.tron.sdk.client.TronNodeClient;
import com.nb.tron.sdk.enums.TronNodeRole;
import com.nb.tron.sdk.model.TronNodeHeight;
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
        when(nodeClient.getHeadHeight(fullNode.toSdkEndpoint()))
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
        when(nodeClient.getBlockHeaderByHeight(failedNode.toSdkEndpoint(), 0L))
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
        when(nodeClient.getBlockHeaderByHeight(wrongNetworkNode.toSdkEndpoint(), 0L))
            .thenReturn(height(wrongNetworkNode, 0L, "other-genesis-block-id"));

        List<TronNodeRuntimeState> states = healthService.refreshNodeStates();

        assertThat(states).singleElement().satisfies(state -> {
            assertThat(state.healthStatus()).isEqualTo(TronNodeHealthStatus.UNKNOWN);
            assertThat(state.latestBlockHeight()).isNull();
        });
        verify(nodeClient, never()).getHeadHeight(wrongNetworkNode.toSdkEndpoint());
    }

    @Test
    void shouldUpdateHeightWithoutResettingHealthState() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE);
        scannerProperties.getNode().setNodes(List.of(fullNode));
        mockHealthyNode(fullNode, 100L);
        healthService.refreshNodeStates();
        when(nodeClient.getHeadHeight(fullNode.toSdkEndpoint()))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT));
        healthService.refreshNodeStates();
        TronNodeRuntimeState beforeUpdate = healthService.findNodeState(fullNode.getCode()).orElseThrow();

        healthService.updateLatestHeight(height(fullNode, 103L, "block-103"), System.nanoTime());

        TronNodeRuntimeState updated = healthService.findNodeState(fullNode.getCode()).orElseThrow();
        assertThat(updated.latestBlockHeight()).isEqualTo(103L);
        assertThat(updated.healthStatus()).isEqualTo(beforeUpdate.healthStatus());
        assertThat(updated.consecutiveFailureCount()).isEqualTo(1);
        assertThat(updated.healthySince()).isEqualTo(beforeUpdate.healthySince());
        assertThat(updated.lastSuccessAt()).isEqualTo(beforeUpdate.lastSuccessAt());
        assertThat(updated.responseTimeMillis()).isEqualTo(beforeUpdate.responseTimeMillis());
    }

    @Test
    void shouldIgnoreHeightFromEarlierRead() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE);
        scannerProperties.getNode().setNodes(List.of(fullNode));
        mockHealthyNode(fullNode, 100L);
        healthService.refreshNodeStates();
        long startedAt = healthService.findNodeState(fullNode.getCode()).orElseThrow().heightReadStartedAt();

        healthService.updateLatestHeight(height(fullNode, 103L, "block-103"), startedAt + 2);
        healthService.updateLatestHeight(height(fullNode, 101L, "block-101"), startedAt + 1);

        assertThat(healthService.findNodeState(fullNode.getCode()).orElseThrow().latestBlockHeight())
            .isEqualTo(103L);
    }

    @Test
    void shouldKeepNewerHeightWhenEarlierHealthCheckReturnsLater() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE);
        scannerProperties.getNode().setNodes(List.of(fullNode));
        mockHealthyNode(fullNode, 100L);
        healthService.refreshNodeStates();
        when(nodeClient.getHeadHeight(fullNode.toSdkEndpoint())).thenAnswer(invocation -> {
            // 健康检查尚未返回时，扫描查询已经读到 103 并回写；检查随后返回旧高度 100。
            healthService.updateLatestHeight(height(fullNode, 103L, "block-103"), System.nanoTime());
            return height(fullNode, 100L, "block-100");
        });

        healthService.refreshNodeStates();

        TronNodeRuntimeState state = healthService.findNodeState(fullNode.getCode()).orElseThrow();
        assertThat(state.latestBlockHeight()).isEqualTo(103L);
        assertThat(state.healthStatus()).isEqualTo(TronNodeHealthStatus.HEALTHY);
    }

    @Test
    void shouldAcceptLowerHeightFromNewerRead() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE);
        scannerProperties.getNode().setNodes(List.of(fullNode));
        mockHealthyNode(fullNode, 100L);
        healthService.refreshNodeStates();

        healthService.updateLatestHeight(height(fullNode, 99L, "block-99"), System.nanoTime());

        assertThat(healthService.findNodeState(fullNode.getCode()).orElseThrow().latestBlockHeight())
            .isEqualTo(99L);
    }

    private void mockHealthyNode(TronNodeEndpointProperties endpoint, long blockHeight) {
        mockGenesisBlock(endpoint);
        TronNodeHeight latestBlock = height(endpoint, blockHeight, "block-" + blockHeight);
        if (endpoint.getRole() == TronNodeRole.FULL_NODE) {
            when(nodeClient.getHeadHeight(endpoint.toSdkEndpoint())).thenReturn(latestBlock);
        } else {
            when(nodeClient.getSolidHeight(endpoint.toSdkEndpoint())).thenReturn(latestBlock);
        }
    }

    private void mockGenesisBlock(TronNodeEndpointProperties endpoint) {
        when(nodeClient.getBlockHeaderByHeight(endpoint.toSdkEndpoint(), 0L))
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
