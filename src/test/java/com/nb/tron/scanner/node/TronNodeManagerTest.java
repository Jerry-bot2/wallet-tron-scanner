package com.nb.tron.scanner.node;

import com.nb.tron.sdk.block.TronBlockGateway;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.sdk.client.TronNodeClient;
import com.nb.tron.sdk.enums.TronNodeRole;
import com.nb.tron.sdk.enums.TronSdkError;
import com.nb.tron.sdk.exception.TronSdkException;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronNodeHeight;
import com.nb.tron.sdk.node.TronNodeDefinition;
import com.nb.tron.sdk.node.TronNodePool;
import com.nb.tron.sdk.node.TronNodePoolOptions;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scanner节点读取入口测试；节点选择算法由SDK测试覆盖。
 * <p>
 * Author: bin jack
 * Date: 09.10.26
 */
class TronNodeManagerTest {

    private static final String GENESIS = "genesis";

    private final TronNodeClient nodeClient = mock(TronNodeClient.class);

    @Test
    void shouldReadHeadAndImmediatelyExposeNewHeight() {
        TronNodeDefinition primary = node("full-primary", 1);
        TronNodePool nodePool = healthyPool(primary);
        TronNodeManager manager = new TronNodeManager(new TronBlockGateway(nodePool, nodeClient, 2));
        when(nodeClient.getHeadHeight(primary.endpoint())).thenReturn(height(primary, 103L));
        TronBlockData block = block(primary, 101L);
        when(nodeClient.getBlockDataByHeight(primary.endpoint(), 101L)).thenReturn(block);

        assertThat(manager.getHeadHeight().blockHeight()).isEqualTo(103L);
        assertThat(manager.getBlockDataByHeight(101L)).isSameAs(block);
        assertThat(nodePool.findState(primary.nodeCode()).orElseThrow().latestBlockHeight()).isEqualTo(103L);
    }

    @Test
    void shouldSwitchOnceAndStopAfterFallbackFailure() {
        TronNodeDefinition primary = node("full-primary", 1);
        TronNodeDefinition backup = node("full-backup", 2);
        TronNodeDefinition third = node("full-third", 3);
        TronNodePool nodePool = healthyPool(primary, backup, third);
        TronNodeManager manager = new TronNodeManager(new TronBlockGateway(nodePool, nodeClient, 2));
        clearInvocations(nodeClient);
        when(nodeClient.getHeadHeight(primary.endpoint()))
            .thenThrow(TronSdkException.of(TronSdkError.TIMEOUT));
        when(nodeClient.getHeadHeight(backup.endpoint()))
            .thenThrow(TronSdkException.of(TronSdkError.CONNECT_FAILED));

        assertThatThrownBy(manager::getHeadHeight)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_SDK_CALL_FAILED);
        verify(nodeClient, never()).getHeadHeight(third.endpoint());
    }

    @Test
    void shouldKeepSameNodeThroughoutAncestorSearch() {
        TronNodeDefinition primary = node("full-primary", 1);
        TronNodeDefinition backup = node("full-backup", 2);
        TronNodePool nodePool = healthyPool(primary, backup);
        TronNodeManager manager = new TronNodeManager(new TronBlockGateway(nodePool, nodeClient, 2));
        when(nodeClient.getBlockHeaderByHeight(primary.endpoint(), 90L)).thenReturn(height(primary, 90L));
        when(nodeClient.getBlockHeaderByHeight(primary.endpoint(), 95L)).thenReturn(height(primary, 95L));

        TronBlockHeaderReader reader = manager.openBlockHeaderReader(100L);
        reader.getBlockHeaderByHeight(90L);
        manager.startRecoveryCooldown(primary.nodeCode());
        reader.getBlockHeaderByHeight(95L);

        verify(nodeClient).getBlockHeaderByHeight(primary.endpoint(), 90L);
        verify(nodeClient).getBlockHeaderByHeight(primary.endpoint(), 95L);
        verify(nodeClient, never()).getBlockHeaderByHeight(backup.endpoint(), 95L);
    }

    @Test
    void shouldRejectInvalidHeightBeforeCallingNode() {
        TronNodeDefinition primary = node("full-primary", 1);
        TronNodePool nodePool = healthyPool(primary);
        TronNodeManager manager = new TronNodeManager(new TronBlockGateway(nodePool, nodeClient, 2));

        assertThatThrownBy(() -> manager.getBlockDataByHeight(-1L))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_SDK_CONFIG_INVALID);
        verify(nodeClient, never()).getBlockDataByHeight(primary.endpoint(), -1L);
    }

    private TronNodePool healthyPool(TronNodeDefinition... nodes) {
        TronNodePool nodePool = new TronNodePool(
            List.of(nodes),
            new TronNodePoolOptions(GENESIS, 3, 20, Duration.ofSeconds(60)),
            nodeClient);
        for (TronNodeDefinition node : nodes) {
            when(nodeClient.getBlockHeaderByHeight(node.endpoint(), 0L)).thenReturn(
                new TronNodeHeight(node.nodeCode(), 0L, GENESIS, Instant.EPOCH));
            when(nodeClient.getHeadHeight(node.endpoint())).thenReturn(height(node, 100L));
        }
        nodePool.refresh();
        return nodePool;
    }

    private TronNodeDefinition node(String code, int priority) {
        return new TronNodeDefinition(
            code,
            TronNodeRole.FULL_NODE,
            priority,
            URI.create("http://" + code + ".example.com"),
            "");
    }

    private TronNodeHeight height(TronNodeDefinition node, long blockHeight) {
        return new TronNodeHeight(node.nodeCode(), blockHeight, "block-" + blockHeight, Instant.EPOCH);
    }

    private TronBlockData block(TronNodeDefinition node, long blockHeight) {
        return new TronBlockData(
            node.nodeCode(),
            blockHeight,
            "block-" + blockHeight,
            "block-" + (blockHeight - 1),
            Instant.EPOCH,
            List.of(),
            Map.of());
    }
}
