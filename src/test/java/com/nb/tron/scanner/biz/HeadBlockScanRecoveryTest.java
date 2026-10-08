package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.biz.DepositDiscoveryService;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronNodeRuntimeState;
import com.nb.tron.scanner.mq.publisher.DepositDiscoveryPublisher;
import com.nb.tron.scanner.node.TronNodeHealthService;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.sdk.client.TronNodeClient;
import com.nb.tron.sdk.enums.TronNodeRole;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronNodeHeight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * 验证扫描入口的正常、分叉、回退三条流程，回退成功使用正常返回值结束本轮。
 * <p>
 * Author: bin jack
 * Date: 06.10.26
 */
class HeadBlockScanRecoveryTest {

    private final TronScannerProperties properties = new TronScannerProperties();
    private final TronScanCheckpoint checkpoint = new TronScanCheckpoint().setChainNetwork("MAINNET")
        .setLastBlockNumber(1010L).setLastBlockHash("h1010");
    private HeadBlockAncestorFinder finder;
    private HeadScanProgressService progress;
    private TronNodeManager manager;
    private TronAddressIndex addresses;
    private TronCurrencyIndex currencies;
    private DepositDiscoveryService parser;
    private DepositDiscoveryPublisher publisher;
    private HeadBlockScanService scanner;

    @BeforeEach
    void setUp() {
        finder = mock(HeadBlockAncestorFinder.class);
        progress = mock(HeadScanProgressService.class);
        manager = mock(TronNodeManager.class);
        addresses = mock(TronAddressIndex.class);
        currencies = mock(TronCurrencyIndex.class);
        parser = mock(DepositDiscoveryService.class);
        publisher = mock(DepositDiscoveryPublisher.class);
        when(addresses.isReady()).thenReturn(true);
        when(currencies.isReady()).thenReturn(true);
        when(progress.loadCheckpoint()).thenReturn(checkpoint);
        when(finder.findCommonAncestor(checkpoint)).thenReturn(summary(1010));
        when(manager.getHeadHeight()).thenReturn(height(1010));
        when(manager.getBlockHeaderByHeight(anyLong())).thenAnswer(i -> height(i.getArgument(0)));
        when(parser.discover(any())).thenReturn(List.of());
        when(progress.advance(any(), any())).thenAnswer(i -> {
            TronBlockData data = i.getArgument(1);
            return new TronScanCheckpoint().setChainNetwork("MAINNET")
                .setLastBlockNumber(data.blockHeight()).setLastBlockHash(data.blockId());
        });
        scanner = scanner(manager);
    }

    @Test
    void shouldContinueWithoutSearchingHistoryWhenCheckpointMatches() {
        assertThat(scanner.scanBlocks()).isZero();
        verifyNoInteractions(finder);
        verify(progress, never()).rewind(any(), any());
    }

    @Test
    void shouldKeepProgressWhenCheckpointHeaderReadFails() {
        BizException failure = BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT);
        when(manager.getBlockHeaderByHeight(1010)).thenThrow(failure);

        assertThatThrownBy(scanner::scanBlocks).isSameAs(failure);
        assertThat(checkpoint.getLastBlockNumber()).isEqualTo(1010);
        verifyNoInteractions(finder, parser, publisher);
        verify(progress, never()).rewind(any(), any());
        verify(progress, never()).advance(any(), any());
    }

    @Test
    void shouldRewindToRecentCommonBlockAndEndNormally() {
        when(manager.getBlockHeaderByHeight(1010)).thenReturn(new TronNodeHeight("full", 1010, "new1010", Instant.EPOCH));
        when(finder.findCommonAncestor(checkpoint)).thenReturn(summary(1008));
        assertThat(scanner.scanBlocks()).isZero();
        verify(progress).rewind(checkpoint, summary(1008));
        verifyNoInteractions(parser, publisher);
        verify(manager, never()).getHeadHeight();
    }

    @Test
    void shouldCheckParentWithoutAdditionalSearchForConnectedBlock() {
        when(manager.getHeadHeight()).thenReturn(height(1011));
        when(manager.getBlockDataByHeight(1011)).thenReturn(block(1011, "h1010"));
        assertThat(scanner.scanBlocks()).isEqualTo(1);
        verifyNoInteractions(finder);
        verify(progress, never()).rewind(any(), any());
    }

    @Test
    void shouldRejectWrongHeightBeforeParsing() {
        when(manager.getHeadHeight()).thenReturn(height(1011));
        when(manager.getBlockDataByHeight(1011)).thenReturn(block(1012, "h1010"));
        assertThatThrownBy(scanner::scanBlocks).isInstanceOf(BizException.class);
        verifyNoInteractions(parser, publisher);
        verify(progress, never()).rewind(any(), any());
        verify(progress, never()).advance(any(), any());
    }

    @Test
    void shouldRecoverForkThatOccursDuringCurrentRound() {
        when(manager.getHeadHeight()).thenReturn(height(1011));
        when(manager.getBlockDataByHeight(1011)).thenReturn(block(1011, "new1010"));
        when(finder.findCommonAncestor(checkpoint)).thenReturn(summary(1008));
        assertThat(scanner.scanBlocks()).isZero();
        verify(progress).rewind(checkpoint, summary(1008));
        verifyNoInteractions(parser, publisher);
    }

    @Test
    void shouldCooldownBadBlockNodeWhenCheckpointStillMatches() {
        when(manager.getHeadHeight()).thenReturn(height(1011));
        when(manager.getBlockDataByHeight(1011)).thenReturn(block(1011, "wrong-parent"));
        assertThat(scanner.scanBlocks()).isZero();
        verify(manager).startRecoveryCooldown("full");
        verifyNoInteractions(parser, publisher);
        verify(progress, never()).rewind(any(), any());
    }

    @Test
    void shouldUseBackupOnNextReadAfterRejectingInconsistentBlock() {
        TronNodeEndpointProperties primary = endpoint("full-primary", 1);
        TronNodeEndpointProperties backup = endpoint("full-backup", 2);
        properties.getNode().setNodes(List.of(primary, backup));
        TronNodeHealthService health = mock(TronNodeHealthService.class);
        TronNodeClient client = mock(TronNodeClient.class);
        Instant healthySince = Instant.now().minusSeconds(120);
        when(health.getNodeStates()).thenReturn(List.of(
            TronNodeRuntimeState.success(primary.getCode(), TronNodeRole.FULL_NODE, null, 1011, 1, healthySince, System.nanoTime()),
            TronNodeRuntimeState.success(backup.getCode(), TronNodeRole.FULL_NODE, null, 1011, 1, healthySince, System.nanoTime())));
        TronNodeManager realManager = new TronNodeManager(properties, health, client);
        when(client.getHeadHeight(any())).thenReturn(height(1011));
        when(client.getBlockHeaderByHeight(any(), eq(1010L))).thenReturn(height(1010));
        when(client.getBlockDataByHeight(primary.toSdkEndpoint(), 1011)).thenReturn(new TronBlockData(primary.getCode(),
            1011, "h1011", "wrong-parent", Instant.EPOCH, List.of(), Map.of()));
        when(client.getBlockDataByHeight(backup.toSdkEndpoint(), 1011)).thenReturn(new TronBlockData(backup.getCode(),
            1011, "h1011", "h1010", Instant.EPOCH, List.of(), Map.of()));
        HeadBlockScanService realScanner = scanner(realManager);

        assertThat(realScanner.scanBlocks()).isZero();
        assertThat(realScanner.scanBlocks()).isEqualTo(1);
        verify(client).getBlockDataByHeight(primary.toSdkEndpoint(), 1011);
        verify(client).getBlockDataByHeight(backup.toSdkEndpoint(), 1011);
        verify(progress, never()).rewind(any(), any());
    }

    @Test
    void shouldNotRewindWhenAncestorNodeReadFails() {
        when(manager.getBlockHeaderByHeight(1010)).thenReturn(new TronNodeHeight("full", 1010, "new1010", Instant.EPOCH));
        when(finder.findCommonAncestor(checkpoint)).thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT));
        assertThatThrownBy(scanner::scanBlocks).isInstanceOf(BizException.class);
        verifyNoInteractions(parser, publisher);
        verify(manager, never()).getHeadHeight();
        verify(progress, never()).rewind(any(), any());
    }

    @Test
    void shouldKeepProgressAndReportMissingCommonBlock() {
        when(manager.getBlockHeaderByHeight(1010)).thenReturn(new TronNodeHeight("full", 1010, "new1010", Instant.EPOCH));
        when(finder.findCommonAncestor(checkpoint)).thenReturn(null);

        assertThatThrownBy(scanner::scanBlocks).isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_COMMON_ANCESTOR_NOT_FOUND);
        assertThat(checkpoint.getLastBlockNumber()).isEqualTo(1010);
        verify(progress, never()).rewind(any(), any());
        verify(progress, never()).advance(any(), any());
        verifyNoInteractions(parser, publisher);
    }

    @Test
    void shouldPropagateFailedRewindAndNeverContinueScanning() {
        when(manager.getBlockHeaderByHeight(1010)).thenReturn(new TronNodeHeight("full", 1010, "new1010", Instant.EPOCH));
        when(finder.findCommonAncestor(checkpoint)).thenReturn(summary(1008));
        doThrow(new IllegalStateException("rollback failed")).when(progress).rewind(any(), any());
        assertThatThrownBy(scanner::scanBlocks).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(parser, publisher);
        verify(manager, never()).getHeadHeight();
    }

    @Test
    void shouldScanFirstConfiguredBlockUsingInitializedProgress() {
        TronScanCheckpoint initial = new TronScanCheckpoint().setChainNetwork("MAINNET")
            .setLastBlockNumber(999L).setLastBlockHash("h999");
        when(progress.loadCheckpoint()).thenReturn(initial);
        when(finder.findCommonAncestor(initial)).thenReturn(summary(999));
        when(manager.getHeadHeight()).thenReturn(height(1000));
        when(manager.getBlockDataByHeight(1000)).thenReturn(block(1000, "h999"));

        assertThat(scanner.scanBlocks()).isEqualTo(1);
        verify(progress).loadCheckpoint();
        verify(manager).getBlockHeaderByHeight(999);
    }

    @Test
    void shouldStopRoundWhenProgressInitializationFails() {
        BizException failure = BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT);
        when(progress.loadCheckpoint()).thenThrow(failure);

        assertThatThrownBy(scanner::scanBlocks).isSameAs(failure);
        verifyNoInteractions(manager, finder, parser, publisher);
    }

    @Test
    void shouldScanGenesisUsingSentinelProgressWithoutNegativeHeaderRead() {
        TronScanCheckpoint initial = new TronScanCheckpoint().setChainNetwork("MAINNET")
            .setLastBlockNumber(-1L).setLastBlockHash("");
        when(progress.loadCheckpoint()).thenReturn(initial);
        when(manager.getHeadHeight()).thenReturn(height(0));
        when(manager.getBlockDataByHeight(0)).thenReturn(block(0, ""));

        assertThat(scanner.scanBlocks()).isEqualTo(1);
        verify(manager, never()).getBlockHeaderByHeight(-1);
        verifyNoInteractions(finder);
        verify(progress, never()).rewind(any(), any());
    }

    @Test
    void shouldFinishRoundWithoutRewindWhenBranchRecoversBeforeForkHandling() {
        when(manager.getBlockHeaderByHeight(1010))
            .thenReturn(new TronNodeHeight("full", 1010, "changed1010", Instant.EPOCH), height(1010));

        assertThat(scanner.scanBlocks()).isZero();
        verify(finder).findCommonAncestor(checkpoint);
        verify(manager).getBlockHeaderByHeight(1010);
        verify(manager, never()).getHeadHeight();
        verify(progress, never()).rewind(any(), any());
        verify(progress, never()).advance(any(), any());
        verifyNoInteractions(parser, publisher);
    }

    private HeadBlockScanService scanner(TronNodeManager nodeManager) {
        return new HeadBlockScanService(properties, addresses, currencies, nodeManager, parser, publisher,
            new HeadBlockContinuityService(nodeManager, finder, progress), progress);
    }

    private TronScannedBlock summary(long height) {
        return new TronScannedBlock().setChainNetwork("MAINNET").setBlockNumber(height).setBlockHash("h" + height);
    }

    private TronNodeHeight height(long height) {
        return new TronNodeHeight("full", height, "h" + height, Instant.EPOCH);
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
