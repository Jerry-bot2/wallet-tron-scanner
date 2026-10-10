package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.biz.DepositDiscoveryService;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.HeadBlockCheckResult;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.mq.publisher.DepositDiscoveryPublisher;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronNodeHeight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
@ExtendWith(OutputCaptureExtension.class)
class HeadBlockScanServiceTest {

    private TronScannerProperties scannerProperties;

    private TronNodeManager nodeManager;

    private DepositDiscoveryService blockParser;

    private DepositDiscoveryPublisher depositPublisher;

    private HeadScanProgressService progressService;

    private HeadBlockContinuityService continuityService;

    private HeadBlockScanService scanService;

    @BeforeEach
    void setUp() {
        scannerProperties = new TronScannerProperties();
        scannerProperties.setStartBlockHeight(100L);
        TronAddressIndex addressIndex = mock(TronAddressIndex.class);
        TronCurrencyIndex currencyIndex = mock(TronCurrencyIndex.class);
        nodeManager = mock(TronNodeManager.class);
        blockParser = mock(DepositDiscoveryService.class);
        depositPublisher = mock(DepositDiscoveryPublisher.class);
        progressService = mock(HeadScanProgressService.class);
        continuityService = mock(HeadBlockContinuityService.class);
        when(continuityService.checkCheckpoint(any())).thenReturn(new HeadBlockCheckResult(false));
        when(continuityService.isNextBlockContinuous(any(), any())).thenReturn(true);
        when(addressIndex.isReady()).thenReturn(true);
        when(currencyIndex.isReady()).thenReturn(true);
        scanService = new HeadBlockScanService(
            scannerProperties,
            addressIndex,
            currencyIndex,
            nodeManager,
            blockParser,
            depositPublisher,
            continuityService,
            progressService);
    }

    @Test
    void shouldPublishBeforeAdvancingCheckpoint(CapturedOutput output) {
        TronScanCheckpoint checkpoint = checkpoint(99L, "block-99");
        TronBlockData blockData = blockData(100L);
        List<TronDepositEvent> deposits = List.of(deposit(100L));
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData);
        when(blockParser.discover(blockData)).thenReturn(deposits);
        when(progressService.advance(checkpoint(99L, "block-99"), blockData(100L)))
            .thenReturn(checkpoint(100L, "block-100"));

        int scannedCount = scanService.scanBlocks();

        assertThat(scannedCount).isEqualTo(1);
        InOrder processingOrder = inOrder(blockParser, depositPublisher, progressService);
        processingOrder.verify(blockParser).discover(blockData);
        processingOrder.verify(depositPublisher).publishAndWait(blockData, deposits);
        processingOrder.verify(progressService).advance(checkpoint(99L, "block-99"), blockData(100L));
        assertThat(output).contains("completed=true");
        assertThat(output).contains("elapsedMillis=", "averageBlockMillis=", "nodeReadMillis=", "kafkaAckMillis=", "progressCommitMillis=");
        assertThat(output.getOut().lines().filter(line -> line.contains("TRON Head扫描本轮结束")).count()).isEqualTo(1L);
        verify(progressService, times(1)).loadCheckpoint();
        verify(nodeManager, times(1)).getHeadHeight();
        verify(nodeManager, times(1)).getBlockDataByHeight(100L);
    }

    @Test
    void shouldAdvanceEmptyBlockWithoutPublishingMessage() {
        TronScanCheckpoint checkpoint = checkpoint(99L, "block-99");
        TronBlockData blockData = blockData(100L);
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData);
        when(blockParser.discover(blockData)).thenReturn(List.of());
        when(progressService.advance(checkpoint(99L, "block-99"), blockData(100L)))
            .thenReturn(checkpoint(100L, "block-100"));

        assertThat(scanService.scanBlocks()).isEqualTo(1);

        verify(depositPublisher, never()).publishAndWait(any(), anyList());
        verify(progressService).advance(checkpoint(99L, "block-99"), blockData(100L));
    }

    @Test
    void shouldRetrySameBlockOnNextRunWhenKafkaPublishFails() {
        RuntimeException failure = new RuntimeException("publish failed");
        TronScanCheckpoint checkpoint = checkpoint(99L, "block-99");
        TronBlockData blockData = blockData(100L);
        List<TronDepositEvent> deposits = List.of(deposit(100L));
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData);
        when(blockParser.discover(blockData)).thenReturn(deposits);
        doThrow(failure).doNothing().when(depositPublisher).publishAndWait(blockData, deposits);
        when(progressService.advance(checkpoint(99L, "block-99"), blockData(100L)))
            .thenReturn(checkpoint(100L, "block-100"));

        assertThatThrownBy(scanService::scanBlocks).isSameAs(failure);
        verify(progressService, never()).advance(any(), any());
        assertThat(scanService.scanBlocks()).isEqualTo(1);

        verify(nodeManager, times(2)).getBlockDataByHeight(100L);
        verify(depositPublisher, times(2)).publishAndWait(blockData, deposits);
        verify(progressService).advance(checkpoint(99L, "block-99"), blockData(100L));
    }

    @Test
    void shouldRetrySameBlockOnNextRunWhenBlockReadFails() {
        BizException exception = BizException.of(ScannerBizErrCode.TRON_SDK_CALL_FAILED);
        when(progressService.loadCheckpoint())
            .thenReturn(checkpoint(99L, "block-99"));
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L))
            .thenThrow(exception)
            .thenReturn(blockData(100L));
        when(blockParser.discover(any(TronBlockData.class))).thenReturn(List.of());
        when(progressService.advance(checkpoint(99L, "block-99"), blockData(100L)))
            .thenReturn(checkpoint(100L, "block-100"));

        assertThatThrownBy(scanService::scanBlocks).isSameAs(exception);

        verify(blockParser, never()).discover(any());
        verify(progressService, never()).advance(any(), any());
        assertThat(scanService.scanBlocks()).isEqualTo(1);
        verify(nodeManager, times(2)).getBlockDataByHeight(100L);
        verify(depositPublisher, never()).publishAndWait(any(), anyList());
    }

    @Test
    void shouldRetrySameBlockOnNextRunWhenBlockParsingFails() {
        BizException exception = BizException.of(ScannerBizErrCode.TRON_SDK_DATA_INVALID);
        TronBlockData blockData = blockData(100L);
        when(progressService.loadCheckpoint())
            .thenReturn(checkpoint(99L, "block-99"));
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData);
        when(blockParser.discover(blockData))
            .thenThrow(exception)
            .thenReturn(List.of());
        when(progressService.advance(checkpoint(99L, "block-99"), blockData(100L)))
            .thenReturn(checkpoint(100L, "block-100"));

        assertThatThrownBy(scanService::scanBlocks).isSameAs(exception);

        verify(depositPublisher, never()).publishAndWait(any(), anyList());
        verify(progressService, never()).advance(any(), any());
        assertThat(scanService.scanBlocks()).isEqualTo(1);
        verify(nodeManager, times(2)).getBlockDataByHeight(100L);
        verify(blockParser, times(2)).discover(blockData);
    }

    @Test
    void shouldResendSameBlockWhenCheckpointAdvanceFails() {
        TronScanCheckpoint checkpoint = checkpoint(99L, "block-99");
        TronBlockData blockData = blockData(100L);
        List<TronDepositEvent> deposits = List.of(deposit(100L));
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData);
        when(blockParser.discover(blockData)).thenReturn(deposits);
        when(progressService.advance(checkpoint(99L, "block-99"), blockData(100L)))
            .thenThrow(new DataAccessResourceFailureException("checkpoint update failed"))
            .thenReturn(checkpoint(100L, "block-100"));

        assertThatThrownBy(scanService::scanBlocks)
            .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(scanService.scanBlocks()).isEqualTo(1);

        verify(depositPublisher, times(2)).publishAndWait(blockData, deposits);
        verify(progressService, times(2)).advance(checkpoint(99L, "block-99"), blockData(100L));
    }

    @Test
    void shouldResumeAfterLastSuccessfulBlockWhenLaterBlockFails() {
        RuntimeException failure = new RuntimeException("publish failed");
        TronBlockData block100 = blockData(100L);
        TronBlockData block101 = blockData(101L);
        List<TronDepositEvent> deposits = List.of(deposit(101L));
        when(progressService.loadCheckpoint())
            .thenReturn(checkpoint(99L, "block-99"), checkpoint(100L, "block-100"));
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(102L), nodeHeight(101L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(block100);
        when(nodeManager.getBlockDataByHeight(101L)).thenReturn(block101);
        when(blockParser.discover(block100)).thenReturn(List.of());
        when(blockParser.discover(block101)).thenReturn(deposits);
        doThrow(failure).doNothing().when(depositPublisher).publishAndWait(block101, deposits);
        when(progressService.advance(checkpoint(99L, "block-99"), blockData(100L)))
            .thenReturn(checkpoint(100L, "block-100"));
        when(progressService.advance(checkpoint(100L, "block-100"), blockData(101L)))
            .thenReturn(checkpoint(101L, "block-101"));

        assertThatThrownBy(scanService::scanBlocks).isSameAs(failure);
        verify(progressService).advance(checkpoint(99L, "block-99"), blockData(100L));
        verify(progressService, never()).advance(checkpoint(100L, "block-100"), blockData(101L));
        verify(nodeManager, never()).getBlockDataByHeight(102L);

        assertThat(scanService.scanBlocks()).isEqualTo(1);
        verify(nodeManager).getBlockDataByHeight(100L);
        verify(nodeManager, times(2)).getBlockDataByHeight(101L);
        verify(progressService).advance(checkpoint(100L, "block-100"), blockData(101L));
    }

    @Test
    void shouldStopAtConfiguredBlockLimit() {
        scannerProperties.setMaxBlocksPerRun(2);
        when(progressService.loadCheckpoint())
            .thenReturn(checkpoint(99L, "block-99"));
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(102L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData(100L));
        when(nodeManager.getBlockDataByHeight(101L)).thenReturn(blockData(101L));
        when(blockParser.discover(any(TronBlockData.class))).thenReturn(List.of());
        when(progressService.advance(any(), any()))
            .thenAnswer(invocation -> {
                TronBlockData block = invocation.getArgument(1);
                return checkpoint(block.blockHeight(), block.blockId());
            });

        assertThat(scanService.scanBlocks()).isEqualTo(2);

        verify(nodeManager, never()).getBlockDataByHeight(102L);
    }

    @Test
    void shouldCheckContinuityEvenWhenThereIsNoNewBlock() {
        TronScanCheckpoint checkpoint = checkpoint(100L, "old-block-100");
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        when(continuityService.checkCheckpoint(checkpoint)).thenReturn(new HeadBlockCheckResult(true));

        assertThat(scanService.scanBlocks()).isZero();
        verify(continuityService).checkCheckpoint(checkpoint);
        verify(continuityService).handleFork(checkpoint);
        verify(nodeManager, never()).getHeadHeight();
        verify(nodeManager, never()).getBlockDataByHeight(anyLong());
        verify(depositPublisher, never()).publishAndWait(any(), anyList());
    }

    @Test
    void shouldCheckCheckpointBeforeReadingNewBlocks() {
        TronScanCheckpoint checkpoint = checkpoint(100L, "block-100");
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));

        assertThat(scanService.scanBlocks()).isZero();
        InOrder order = inOrder(continuityService, progressService, nodeManager);
        order.verify(progressService).loadCheckpoint();
        order.verify(continuityService).checkCheckpoint(checkpoint);
        order.verify(nodeManager).getHeadHeight();
        verify(continuityService, never()).handleFork(any());
    }

    @Test
    void shouldStopRoundWhenNextBlockDoesNotConnect() {
        when(progressService.loadCheckpoint()).thenReturn(checkpoint(99L, "block-99"));
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(101L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData(100L));
        when(nodeManager.getBlockDataByHeight(101L)).thenReturn(blockData(101L));
        when(blockParser.discover(blockData(100L))).thenReturn(List.of());
        when(progressService.advance(checkpoint(99L, "block-99"), blockData(100L)))
            .thenReturn(checkpoint(100L, "block-100"));
        when(continuityService.isNextBlockContinuous(checkpoint(100L, "block-100"), blockData(101L)))
            .thenReturn(false);

        assertThat(scanService.scanBlocks()).isZero();

        verify(progressService, never()).advance(checkpoint(100L, "block-100"), blockData(101L));
        verify(continuityService).handleParentHashMismatch(checkpoint(100L, "block-100"), blockData(101L));
    }

    @Test
    void shouldNotStartNormalScanWhenForkHandlingFails() {
        TronScanCheckpoint checkpoint = checkpoint(100L, "block-100");
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        when(continuityService.checkCheckpoint(checkpoint)).thenReturn(new HeadBlockCheckResult(true));
        doThrow(new IllegalStateException("rewind failed")).when(continuityService).handleFork(checkpoint);

        assertThatThrownBy(scanService::scanBlocks).isInstanceOf(IllegalStateException.class);
        verify(nodeManager, never()).getHeadHeight();
        verify(progressService, never()).advance(any(), any());
        verify(depositPublisher, never()).publishAndWait(any(), anyList());
    }

    private TronScanCheckpoint checkpoint(long height, String hash) {
        return new TronScanCheckpoint().setChainNetwork("MAINNET")
            .setLastBlockNumber(height).setLastBlockHash(hash);
    }

    private TronNodeHeight nodeHeight(long blockHeight) {
        return new TronNodeHeight(
            "full-primary",
            blockHeight,
            "block-" + blockHeight,
            Instant.parse("2026-10-03T12:00:00Z"));
    }

    private TronBlockData blockData(long blockHeight) {
        return new TronBlockData(
            "full-primary",
            blockHeight,
            "block-" + blockHeight,
            "block-" + (blockHeight - 1),
            Instant.parse("2026-10-03T12:00:00Z"),
            List.of(),
            Map.of());
    }

    private TronDepositEvent deposit(long blockHeight) {
        return new TronDepositEvent(
            "USDT",
            "TUsdtContract",
            "tx-1",
            0,
            blockHeight,
            "block-" + blockHeight,
            Instant.parse("2026-10-03T12:00:00Z"),
            "TSender",
            "TReceiver",
            BigInteger.ONE);
    }
}
