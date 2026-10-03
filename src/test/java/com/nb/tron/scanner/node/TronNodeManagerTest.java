package com.nb.tron.scanner.node;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronNodeHealthStatus;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronNodeRuntimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class TronNodeManagerTest {

    private TronScannerProperties scannerProperties;

    private TronNodeHealthService nodeHealthService;

    private TronNodeManager nodeManager;

    @BeforeEach
    void setUp() {
        scannerProperties = new TronScannerProperties();
        nodeHealthService = mock(TronNodeHealthService.class);
        nodeManager = new TronNodeManager(scannerProperties, nodeHealthService);
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
            healthySince);
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
            null);
    }
}
