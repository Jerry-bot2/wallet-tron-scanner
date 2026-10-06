package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.client.tron.TronNodeClient;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronNodeRuntimeState;
import com.nb.tron.scanner.node.TronNodeHealthService;
import com.nb.tron.scanner.node.TronNodeManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * Author: bin jack
 * Date: 05.10.26
 */
class HeadBlockContinuityServiceTest {

    private HeadBlockAncestorFinder ancestorFinder;
    private HeadScanProgressService progressService;
    private TronNodeManager nodeManager;
    private HeadBlockContinuityService continuity;
    private final TronScanCheckpoint checkpoint = new TronScanCheckpoint().setChainNetwork("MAINNET")
        .setLastBlockNumber(1010L).setLastBlockHash("h1010");

    @BeforeEach
    void setUp() {
        ancestorFinder = mock(HeadBlockAncestorFinder.class);
        progressService = mock(HeadScanProgressService.class);
        nodeManager = mock(TronNodeManager.class);
        continuity = new HeadBlockContinuityService(ancestorFinder, progressService, nodeManager);
    }

    @Test
    void shouldContinueWhenCheckpointMatches() {
        when(ancestorFinder.findCommonAncestor(checkpoint)).thenReturn(summary(1010));
        assertThatNoException().isThrownBy(() -> continuity.checkCheckpoint(checkpoint));
        verifyNoInteractions(progressService);
    }

    @Test
    void shouldRewindChangedCheckpointThenEndRound() {
        TronScannedBlock common = summary(1008);
        when(ancestorFinder.findCommonAncestor(checkpoint)).thenReturn(common);
        assertThatThrownBy(() -> continuity.checkCheckpoint(checkpoint)).isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ScannerBizErrCode.HEAD_SCAN_FORK_DETECTED);
        verify(progressService).rewind(checkpoint, common);
        verifyNoInteractions(nodeManager);
    }

    @Test
    void shouldAcceptNextBlockWithoutSearchingHistory() {
        continuity.checkNextBlock(checkpoint, block(1011, "h1010"));
        verifyNoInteractions(ancestorFinder, progressService);
    }

    @Test
    void shouldRejectWrongHeightBeforeParsing() {
        assertThatThrownBy(() -> continuity.checkNextBlock(checkpoint, block(1012, "h1010")))
            .isInstanceOf(BizException.class);
        verifyNoInteractions(ancestorFinder, progressService);
    }

    @Test
    void shouldFindCommonBlockWhenParentHashDiffers() {
        TronScannedBlock common = summary(1008);
        when(ancestorFinder.findCommonAncestor(checkpoint)).thenReturn(common);
        assertThatThrownBy(() -> continuity.checkNextBlock(checkpoint, block(1011, "new1010")))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ScannerBizErrCode.HEAD_SCAN_FORK_DETECTED);
        verify(progressService).rewind(checkpoint, common);
    }

    @Test
    void shouldNotRewindIfNextBlockConflictsButCheckpointStillMatches() {
        when(ancestorFinder.findCommonAncestor(checkpoint)).thenReturn(summary(1010));
        assertThatThrownBy(() -> continuity.checkNextBlock(checkpoint, block(1011, "wrong-parent")))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        verifyNoInteractions(progressService);
        verify(nodeManager).startRecoveryCooldown("full");
    }

    @Test
    void shouldUseBackupOnNextReadAfterRejectingInconsistentBlock() {
        TronScannerProperties properties = new TronScannerProperties();
        TronNodeEndpointProperties primary = endpoint("full-primary", 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", 2);
        properties.getNode().setNodes(List.of(primary, backup));
        TronNodeHealthService healthService = mock(TronNodeHealthService.class);
        TronNodeClient nodeClient = mock(TronNodeClient.class);
        Instant healthySince = Instant.now().minusSeconds(120);
        when(healthService.getNodeStates()).thenReturn(List.of(
            TronNodeRuntimeState.success(primary.getCode(), TronNodeRole.FULL_NODE, null, 1011, 1, healthySince),
            TronNodeRuntimeState.success(backup.getCode(), TronNodeRole.FULL_NODE, null, 1011, 1, healthySince)));
        TronNodeManager manager = new TronNodeManager(properties, healthService, nodeClient);
        HeadBlockContinuityService checker = new HeadBlockContinuityService(ancestorFinder, progressService, manager);
        TronBlockData invalidBlock = new TronBlockData(primary.getCode(), 1011, "h1011", "wrong-parent",
            Instant.EPOCH, List.of(), Map.of());
        TronBlockData validBlock = new TronBlockData(backup.getCode(), 1011, "h1011", "h1010",
            Instant.EPOCH, List.of(), Map.of());
        when(nodeClient.getBlockDataByHeight(primary, 1011)).thenReturn(invalidBlock);
        when(nodeClient.getBlockDataByHeight(backup, 1011)).thenReturn(validBlock);
        when(ancestorFinder.findCommonAncestor(checkpoint)).thenReturn(summary(1010));

        assertThatThrownBy(() -> checker.checkNextBlock(checkpoint, manager.getBlockDataByHeight(1011)))
            .isInstanceOf(BizException.class);

        // 两个节点的健康检查仍成功，但异常节点被冷却；下次实际读取改用备用节点。
        TronBlockData nextRead = manager.getBlockDataByHeight(1011);
        assertThat(nextRead).isSameAs(validBlock);
        checker.checkNextBlock(checkpoint, nextRead);
        verify(nodeClient).getBlockDataByHeight(primary, 1011);
        verify(nodeClient).getBlockDataByHeight(backup, 1011);
        verifyNoInteractions(progressService);
    }

    @Test
    void shouldNotChangeDatabaseWhenAncestorLookupFails() {
        when(ancestorFinder.findCommonAncestor(checkpoint))
            .thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT));
        assertThatThrownBy(() -> continuity.checkCheckpoint(checkpoint)).isInstanceOf(BizException.class);
        verifyNoInteractions(progressService);
    }

    @Test
    void shouldRestartOriginalRangeWhenNoRetainedBlockMatches() {
        when(ancestorFinder.findCommonAncestor(checkpoint))
            .thenThrow(BizException.of(ScannerBizErrCode.HEAD_SCAN_COMMON_ANCESTOR_NOT_FOUND));
        TronScanCheckpoint restarted = new TronScanCheckpoint().setChainNetwork("MAINNET")
            .setLastBlockNumber(99L).setLastBlockHash("new99");
        when(progressService.restartFromInitialBoundary(checkpoint)).thenReturn(restarted);

        assertThatThrownBy(() -> continuity.checkCheckpoint(checkpoint)).isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(ScannerBizErrCode.HEAD_SCAN_FORK_DETECTED);
        verify(progressService).restartFromInitialBoundary(checkpoint);
    }

    @Test
    void shouldSkipCheckpointLookupBeforeGenesis() {
        TronScanCheckpoint initial = new TronScanCheckpoint().setChainNetwork("MAINNET")
            .setLastBlockNumber(-1L).setLastBlockHash("");
        continuity.checkCheckpoint(initial);
        continuity.checkNextBlock(initial, block(0, ""));
        verifyNoInteractions(ancestorFinder, progressService);
    }

    private TronScannedBlock summary(long height) {
        return new TronScannedBlock().setChainNetwork("MAINNET").setBlockNumber(height).setBlockHash("h" + height);
    }

    private TronNodeEndpointProperties endpoint(String code, int priority) {
        TronNodeEndpointProperties endpoint = new TronNodeEndpointProperties();
        endpoint.setCode(code);
        endpoint.setRole(TronNodeRole.FULL_NODE);
        endpoint.setPriority(priority);
        endpoint.setBaseUrl(URI.create("http://" + code + ".example.com"));
        return endpoint;
    }

    private TronBlockData block(long height, String parentHash) {
        return new TronBlockData("full", height, "h" + height, parentHash, Instant.EPOCH, List.of(), Map.of());
    }
}
