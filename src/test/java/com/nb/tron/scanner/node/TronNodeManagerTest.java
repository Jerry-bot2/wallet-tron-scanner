package com.nb.tron.scanner.node;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.client.tron.TronNodeClient;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronNodeHealthStatus;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.model.TronNodeRuntimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class TronNodeManagerTest {

    private TronScannerProperties scannerProperties;

    private TronNodeHealthService nodeHealthService;

    private TronNodeClient nodeClient;

    private TronNodeManager nodeManager;

    @BeforeEach
    void setUp() {
        scannerProperties = new TronScannerProperties();
        nodeHealthService = mock(TronNodeHealthService.class);
        nodeClient = mock(TronNodeClient.class);
        nodeManager = new TronNodeManager(scannerProperties, nodeHealthService, nodeClient);
    }

    @Test
    void shouldReadHeadHeightFromSelectedNode() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        scannerProperties.getNode().setNodes(List.of(primary));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 100L, Instant.now().minusSeconds(120))));
        TronNodeHeight expected = nodeHeight(primary, 100L);
        when(nodeClient.getHeadHeight(primary)).thenReturn(expected);

        assertThat(nodeManager.getHeadHeight()).isSameAs(expected);
    }

    @Test
    void shouldReadNewBlockBeforeNextHealthCheck() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        scannerProperties.getNode().setNodes(List.of(primary));
        initializeRealNodeHealth();
        when(nodeClient.getHeadHeight(primary)).thenReturn(nodeHeight(primary, 1003L));
        TronBlockData expected = blockData(primary, 1001L);
        when(nodeClient.getBlockDataByHeight(primary, 1001L)).thenReturn(expected);

        // 上次检查只到 1000，本轮读到 1003 后，应立即能够读取 1001，不用等健康检查刷新。
        assertThat(nodeManager.getHeadHeight().blockHeight()).isEqualTo(1003L);
        assertThat(nodeManager.getBlockDataByHeight(1001L)).isSameAs(expected);
        assertThat(nodeHealthService.findNodeState(primary.getCode()).orElseThrow().latestBlockHeight())
            .isEqualTo(1003L);
        verify(nodeClient, times(2)).getHeadHeight(primary);
        verify(nodeClient).getBlockHeaderByHeight(primary, 0L);
    }

    @Test
    void shouldUpdateBackupHeightAfterFailover() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", TronNodeRole.FULL_NODE, 2);
        scannerProperties.getNode().setNodes(List.of(primary, backup));
        initializeRealNodeHealth();
        when(nodeClient.getHeadHeight(primary))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT));
        when(nodeClient.getHeadHeight(backup)).thenReturn(nodeHeight(backup, 1003L));
        TronBlockData expected = blockData(backup, 1001L);
        when(nodeClient.getBlockDataByHeight(backup, 1001L)).thenReturn(expected);

        assertThat(nodeManager.getHeadHeight().nodeCode()).isEqualTo(backup.getCode());
        assertThat(nodeManager.getBlockDataByHeight(1001L)).isSameAs(expected);
        assertThat(nodeHealthService.findNodeState(primary.getCode()).orElseThrow().latestBlockHeight())
            .isEqualTo(1000L);
        assertThat(nodeHealthService.findNodeState(backup.getCode()).orElseThrow().latestBlockHeight())
            .isEqualTo(1003L);
        verify(nodeClient, times(2)).getHeadHeight(primary);
        verify(nodeClient, times(2)).getHeadHeight(backup);
        verify(nodeClient, never()).getBlockDataByHeight(primary, 1001L);
    }

    @Test
    void shouldSwitchToBackupWhenHeadReadFails() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", TronNodeRole.FULL_NODE, 2);
        scannerProperties.getNode().setNodes(List.of(primary, backup));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 100L, Instant.now().minusSeconds(120)),
            healthyState(backup, 100L, Instant.now().minusSeconds(120))));
        TronNodeHeight expected = nodeHeight(backup, 100L);
        when(nodeClient.getHeadHeight(primary))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT));
        when(nodeClient.getHeadHeight(backup)).thenReturn(expected);

        assertThat(nodeManager.getHeadHeight()).isSameAs(expected);
        assertThat(nodeManager.selectFullNodeForHead()).isSameAs(backup);
    }

    @Test
    void shouldReadSolidHeightFromSelectedSolidityNode() {
        TronNodeEndpointProperties solidityNode = endpoint(
            "solidity-primary",
            TronNodeRole.SOLIDITY_NODE,
            1);
        scannerProperties.getNode().setNodes(List.of(solidityNode));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(solidityNode, 90L, Instant.now().minusSeconds(120))));
        TronNodeHeight expected = nodeHeight(solidityNode, 90L);
        when(nodeClient.getSolidHeight(solidityNode)).thenReturn(expected);

        assertThat(nodeManager.getSolidHeight()).isSameAs(expected);
    }

    @Test
    void shouldKeepHeadAndSolidHeightAsDifferentChainViews() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties solidityNode = endpoint(
            "solidity-primary",
            TronNodeRole.SOLIDITY_NODE,
            1);
        scannerProperties.getNode().setNodes(List.of(fullNode, solidityNode));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(fullNode, 110L, Instant.now().minusSeconds(120)),
            healthyState(solidityNode, 100L, Instant.now().minusSeconds(120))));
        when(nodeClient.getHeadHeight(fullNode)).thenReturn(nodeHeight(fullNode, 110L));
        when(nodeClient.getSolidHeight(solidityNode)).thenReturn(nodeHeight(solidityNode, 100L));

        TronNodeHeight headHeight = nodeManager.getHeadHeight();
        TronNodeHeight solidHeight = nodeManager.getSolidHeight();

        assertThat(headHeight.blockHeight()).isEqualTo(110L);
        assertThat(solidHeight.blockHeight()).isEqualTo(100L);
    }

    @Test
    void shouldReadSameBlockIdFromFullNodeAtSolidHeight() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties solidityNode = endpoint(
            "solidity-primary",
            TronNodeRole.SOLIDITY_NODE,
            1);
        scannerProperties.getNode().setNodes(List.of(fullNode, solidityNode));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(fullNode, 110L, Instant.now().minusSeconds(120)),
            healthyState(solidityNode, 100L, Instant.now().minusSeconds(120))));
        TronNodeHeight solidHeight = nodeHeight(solidityNode, 100L);
        TronBlockData solidBlock = blockData(fullNode, 100L);
        when(nodeClient.getSolidHeight(solidityNode)).thenReturn(solidHeight);
        when(nodeClient.getBlockDataByHeight(fullNode, 100L)).thenReturn(solidBlock);

        TronNodeHeight actualSolidHeight = nodeManager.getSolidHeight();
        TronBlockData actualSolidBlock = nodeManager.getBlockDataByHeight(
            actualSolidHeight.blockHeight());

        assertThat(actualSolidBlock.blockId()).isEqualTo(actualSolidHeight.blockId());
    }

    @Test
    void shouldFailExplicitlyWhenSolidityNodeIsUnavailable() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties solidityNode = endpoint(
            "solidity-primary",
            TronNodeRole.SOLIDITY_NODE,
            1);
        scannerProperties.getNode().setNodes(List.of(fullNode, solidityNode));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(fullNode, 110L, Instant.now().minusSeconds(120)),
            unhealthyState(solidityNode)));

        assertThatThrownBy(nodeManager::getSolidHeight)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_UNAVAILABLE);
        verify(nodeClient, never()).getSolidHeight(solidityNode);
    }

    @Test
    void shouldReadBlockAndReceiptsFromOneSelectedNode() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        scannerProperties.getNode().setNodes(List.of(primary));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 100L, Instant.now().minusSeconds(120))));
        TronBlockData expected = blockData(primary, 90L);
        when(nodeClient.getBlockDataByHeight(primary, 90L)).thenReturn(expected);

        assertThat(nodeManager.getBlockDataByHeight(90L)).isSameAs(expected);
    }

    @Test
    void shouldReadBlockHeaderFromSelectedNode() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        scannerProperties.getNode().setNodes(List.of(primary));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 100L, Instant.now().minusSeconds(120))));
        TronNodeHeight expected = nodeHeight(primary, 90L);
        when(nodeClient.getBlockHeaderByHeight(primary, 90L)).thenReturn(expected);

        assertThat(nodeManager.getBlockHeaderByHeight(90L)).isSameAs(expected);
    }

    @Test
    void shouldRejectInvalidBlockHeightBeforeCallingNode() {
        assertThatThrownBy(() -> nodeManager.getBlockDataByHeight(-1L))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
        verify(nodeClient, never()).getBlockDataByHeight(
            org.mockito.ArgumentMatchers.any(TronNodeEndpointProperties.class),
            org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shouldStopAfterFallbackNodeAlsoFails() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", TronNodeRole.FULL_NODE, 2);
        TronNodeEndpointProperties third = endpoint("full-third", TronNodeRole.FULL_NODE, 3);
        scannerProperties.getNode().setNodes(List.of(primary, backup, third));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 100L, Instant.now().minusSeconds(120)),
            healthyState(backup, 100L, Instant.now().minusSeconds(120)),
            healthyState(third, 100L, Instant.now().minusSeconds(120))));
        when(nodeClient.getHeadHeight(primary))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT));
        when(nodeClient.getHeadHeight(backup))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_CONNECT_FAILED));

        assertThatThrownBy(nodeManager::getHeadHeight)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_CONNECT_FAILED);
        verify(nodeClient, never()).getHeadHeight(third);
    }

    @Test
    void shouldKeepUsingCurrentHealthyNode() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", TronNodeRole.FULL_NODE, 2);
        scannerProperties.getNode().setNodes(List.of(primary, backup));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 100L, Instant.now().minusSeconds(120)),
            healthyState(backup, 100L, Instant.now().minusSeconds(120))));

        assertThat(nodeManager.selectFullNodeForHead()).isSameAs(primary);
        assertThat(nodeManager.selectFullNodeForHead()).isSameAs(primary);
    }

    @Test
    void shouldSelectNodeThatHasReachedRequiredHeight() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", TronNodeRole.FULL_NODE, 2);
        scannerProperties.getNode().setNodes(List.of(primary, backup));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 100L, Instant.now().minusSeconds(120)),
            healthyState(backup, 110L, Instant.now().minusSeconds(120))));

        assertThat(nodeManager.selectFullNodeForBlock(105L)).isSameAs(backup);
    }

    @Test
    void shouldExcludeClearlyLaggingFullNode() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", TronNodeRole.FULL_NODE, 2);
        scannerProperties.getNode().setNodes(List.of(primary, backup));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 70L, Instant.now().minusSeconds(120)),
            healthyState(backup, 100L, Instant.now().minusSeconds(120))));

        assertThat(nodeManager.selectFullNodeForHead()).isSameAs(backup);
    }

    @Test
    void shouldSwitchToBackupImmediatelyAfterCurrentRequestFails() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", TronNodeRole.FULL_NODE, 2);
        scannerProperties.getNode().setNodes(List.of(primary, backup));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 100L, Instant.now().minusSeconds(120)),
            healthyState(backup, 70L, Instant.now().minusSeconds(120))));

        assertThat(nodeManager.selectFullNodeForHead()).isSameAs(primary);
        assertThat(nodeManager.switchFullNodeForBlock(primary.getCode(), 60L)).isSameAs(backup);
        assertThat(nodeManager.selectFullNodeForBlock(60L)).isSameAs(backup);
    }

    @Test
    void shouldWaitForRecoveryCooldownBeforeReturningToPrimary() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", TronNodeRole.FULL_NODE, 2);
        scannerProperties.getNode().setNodes(List.of(primary, backup));
        when(nodeHealthService.getNodeStates())
            .thenReturn(List.of(
                unhealthyState(primary),
                healthyState(backup, 100L, Instant.now().minusSeconds(120))))
            .thenReturn(List.of(
                healthyState(primary, 100L, Instant.now()),
                healthyState(backup, 100L, Instant.now().minusSeconds(120))))
            .thenReturn(List.of(
                healthyState(primary, 100L, Instant.now().minusSeconds(120)),
                healthyState(backup, 100L, Instant.now().minusSeconds(120))));

        assertThat(nodeManager.selectFullNodeForHead()).isSameAs(backup);
        assertThat(nodeManager.selectFullNodeForHead()).isSameAs(backup);
        assertThat(nodeManager.selectFullNodeForHead()).isSameAs(primary);
    }

    @Test
    void shouldSelectSolidityNodeFromItsOwnCandidatePool() {
        TronNodeEndpointProperties fullNode = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties solidityNode = endpoint("solidity-primary", TronNodeRole.SOLIDITY_NODE, 1);
        scannerProperties.getNode().setNodes(List.of(fullNode, solidityNode));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(fullNode, 100L, Instant.now().minusSeconds(120)),
            healthyState(solidityNode, 90L, Instant.now().minusSeconds(120))));

        assertThat(nodeManager.selectSolidityNode()).isSameAs(solidityNode);
    }

    @Test
    void shouldFailWhenNoNodeIsAvailable() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        scannerProperties.getNode().setNodes(List.of(primary));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(unhealthyState(primary)));

        assertThatThrownBy(nodeManager::selectFullNodeForHead)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_UNAVAILABLE);
    }

    @Test
    void shouldKeepSameNodeThroughoutAncestorSearch() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", TronNodeRole.FULL_NODE, 2);
        scannerProperties.getNode().setNodes(List.of(primary, backup));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 100L, Instant.now().minusSeconds(120)),
            healthyState(backup, 100L, Instant.now().minusSeconds(120))));
        when(nodeClient.getBlockHeaderByHeight(primary, 90L)).thenReturn(nodeHeight(primary, 90L));
        when(nodeClient.getBlockHeaderByHeight(primary, 95L)).thenReturn(nodeHeight(primary, 95L));

        TronBlockHeaderReader reader = nodeManager.openBlockHeaderReader(100L);
        reader.getBlockHeaderByHeight(90L);
        nodeManager.switchFullNodeForBlock(primary.getCode(), 100L);
        reader.getBlockHeaderByHeight(95L);

        verify(nodeClient).getBlockHeaderByHeight(primary, 90L);
        verify(nodeClient).getBlockHeaderByHeight(primary, 95L);
        verify(nodeClient, never()).getBlockHeaderByHeight(backup, 95L);
    }

    @Test
    void shouldEndFailedSearchAndUseBackupForNextSearch() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", TronNodeRole.FULL_NODE, 2);
        scannerProperties.getNode().setNodes(List.of(primary, backup));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 100L, Instant.now().minusSeconds(120)),
            healthyState(backup, 100L, Instant.now().minusSeconds(120))));
        when(nodeClient.getBlockHeaderByHeight(primary, 90L))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT));
        when(nodeClient.getBlockHeaderByHeight(backup, 90L)).thenReturn(nodeHeight(backup, 90L));

        TronBlockHeaderReader reader = nodeManager.openBlockHeaderReader(100L);
        assertThatThrownBy(() -> reader.getBlockHeaderByHeight(90L)).isInstanceOf(BizException.class);
        verify(nodeClient, never()).getBlockHeaderByHeight(backup, 90L);
        assertThat(nodeManager.openBlockHeaderReader(100L).getBlockHeaderByHeight(90L).nodeCode())
            .isEqualTo(backup.getCode());
    }

    @Test
    void shouldNotStartAncestorSearchOnNodeBehindCheckpoint() {
        TronNodeEndpointProperties primary = endpoint("full-primary", TronNodeRole.FULL_NODE, 1);
        scannerProperties.getNode().setNodes(List.of(primary));
        when(nodeHealthService.getNodeStates()).thenReturn(List.of(
            healthyState(primary, 99L, Instant.now().minusSeconds(120))));
        assertThatThrownBy(() -> nodeManager.openBlockHeaderReader(100L))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ScannerBizErrCode.TRON_NODE_UNAVAILABLE);
        verifyNoInteractions(nodeClient);
    }

    private void initializeRealNodeHealth() {
        scannerProperties.setExpectedGenesisBlockId("block-0");
        for (TronNodeEndpointProperties endpoint : scannerProperties.getNode().getNodes()) {
            when(nodeClient.getBlockHeaderByHeight(endpoint, 0L)).thenReturn(nodeHeight(endpoint, 0L));
            when(nodeClient.getHeadHeight(endpoint)).thenReturn(nodeHeight(endpoint, 1000L));
        }
        nodeHealthService = new TronNodeHealthService(scannerProperties, nodeClient);
        nodeHealthService.refreshNodeStates();
        nodeManager = new TronNodeManager(scannerProperties, nodeHealthService, nodeClient);
    }

    private TronNodeEndpointProperties endpoint(String code, TronNodeRole role, int priority) {
        TronNodeEndpointProperties endpoint = new TronNodeEndpointProperties();
        endpoint.setCode(code);
        endpoint.setRole(role);
        endpoint.setPriority(priority);
        endpoint.setBaseUrl(URI.create("http://" + code + ".example.com"));
        return endpoint;
    }

    private TronNodeRuntimeState healthyState(TronNodeEndpointProperties endpoint,
                                              long blockHeight,
                                              Instant healthySince) {
        return new TronNodeRuntimeState(
            endpoint.getCode(),
            endpoint.getRole(),
            TronNodeHealthStatus.HEALTHY,
            blockHeight,
            0,
            10L,
            Instant.now(),
            healthySince,
            System.nanoTime());
    }

    private TronNodeRuntimeState unhealthyState(TronNodeEndpointProperties endpoint) {
        return new TronNodeRuntimeState(
            endpoint.getCode(),
            endpoint.getRole(),
            TronNodeHealthStatus.UNHEALTHY,
            null,
            3,
            10L,
            null,
            null,
            0L);
    }

    private TronNodeHeight nodeHeight(TronNodeEndpointProperties endpoint, long blockHeight) {
        return new TronNodeHeight(
            endpoint.getCode(),
            blockHeight,
            "block-" + blockHeight,
            Instant.now());
    }

    private TronBlockData blockData(TronNodeEndpointProperties endpoint, long blockHeight) {
        return new TronBlockData(
            endpoint.getCode(),
            blockHeight,
            "block-" + blockHeight,
            "block-" + (blockHeight - 1),
            Instant.now(),
            List.of(),
            Map.of());
    }
}
