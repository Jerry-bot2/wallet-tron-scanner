package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.mq.publisher.DepositDiscoveryPublisher;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.parser.TronBlockParser;
import com.nb.tron.scanner.service.ITronScanCheckpointService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
class HeadBlockScanServiceTest {

    private TronScannerProperties scannerProperties;

    private TronNodeManager nodeManager;

    private TronBlockParser blockParser;

    private DepositDiscoveryPublisher depositPublisher;

    private ITronScanCheckpointService checkpointService;

    private HeadBlockScanService scanService;

    @BeforeEach
    void setUp() {
        scannerProperties = new TronScannerProperties();
        scannerProperties.setStartBlockHeight(100L);
        TronAddressIndex addressIndex = mock(TronAddressIndex.class);
        TronCurrencyIndex currencyIndex = mock(TronCurrencyIndex.class);
        nodeManager = mock(TronNodeManager.class);
        blockParser = mock(TronBlockParser.class);
        depositPublisher = mock(DepositDiscoveryPublisher.class);
        checkpointService = mock(ITronScanCheckpointService.class);
        when(addressIndex.isReady()).thenReturn(true);
        when(currencyIndex.isReady()).thenReturn(true);
        scanService = new HeadBlockScanService(
            scannerProperties,
            addressIndex,
            currencyIndex,
            nodeManager,
            blockParser,
            depositPublisher,
            checkpointService);
    }

    @Test
    void shouldPublishBeforeAdvancingCheckpoint() {
        TronScanCheckpoint checkpoint = checkpoint(99L, "block-99");
        TronBlockData blockData = blockData(100L);
        List<TronDepositEvent> deposits = List.of(deposit(100L));
        when(checkpointService.findByNetwork("MAINNET")).thenReturn(checkpoint);
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData);
        when(blockParser.parse(blockData)).thenReturn(deposits);
        when(checkpointService.advance("MAINNET", 99L, 100L, "block-100"))
            .thenReturn(true);

        int scannedCount = scanService.scanBlocks();

        assertThat(scannedCount).isEqualTo(1);
        InOrder processingOrder = inOrder(depositPublisher, checkpointService);
        processingOrder.verify(depositPublisher).publishAndWait(blockData, deposits);
        processingOrder.verify(checkpointService).advance("MAINNET", 99L, 100L, "block-100");
    }

    @Test
    void shouldAdvanceEmptyBlockWithoutPublishingMessage() {
        TronScanCheckpoint checkpoint = checkpoint(99L, "block-99");
        TronBlockData blockData = blockData(100L);
        when(checkpointService.findByNetwork("MAINNET")).thenReturn(checkpoint);
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData);
        when(blockParser.parse(blockData)).thenReturn(List.of());
        when(checkpointService.advance("MAINNET", 99L, 100L, "block-100"))
            .thenReturn(true);

        assertThat(scanService.scanBlocks()).isEqualTo(1);

        verify(depositPublisher, never()).publishAndWait(any(), anyList());
        verify(checkpointService).advance("MAINNET", 99L, 100L, "block-100");
    }

    @Test
    void shouldRetrySameBlockOnNextRunWhenKafkaPublishFails() {
        RuntimeException failure = new RuntimeException("publish failed");
        TronScanCheckpoint checkpoint = checkpoint(99L, "block-99");
        TronBlockData blockData = blockData(100L);
        List<TronDepositEvent> deposits = List.of(deposit(100L));
        when(checkpointService.findByNetwork("MAINNET")).thenReturn(checkpoint);
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData);
        when(blockParser.parse(blockData)).thenReturn(deposits);
        doThrow(failure).doNothing().when(depositPublisher).publishAndWait(blockData, deposits);
        when(checkpointService.advance("MAINNET", 99L, 100L, "block-100"))
            .thenReturn(true);

        assertThatThrownBy(scanService::scanBlocks).isSameAs(failure);
        verify(checkpointService, never()).advance(any(), anyLong(), anyLong(), any());
        assertThat(scanService.scanBlocks()).isEqualTo(1);

        verify(nodeManager, times(2)).getBlockDataByHeight(100L);
        verify(depositPublisher, times(2)).publishAndWait(blockData, deposits);
        verify(checkpointService).advance("MAINNET", 99L, 100L, "block-100");
    }

    @Test
    void shouldRetrySameBlockOnNextRunWhenBlockReadFails() {
        BizException exception = BizException.of(ScannerBizErrCode.TRON_NODE_UNAVAILABLE);
        when(checkpointService.findByNetwork("MAINNET"))
            .thenReturn(checkpoint(99L, "block-99"));
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L))
            .thenThrow(exception)
            .thenReturn(blockData(100L));
        when(blockParser.parse(any(TronBlockData.class))).thenReturn(List.of());
        when(checkpointService.advance("MAINNET", 99L, 100L, "block-100"))
            .thenReturn(true);

        assertThatThrownBy(scanService::scanBlocks).isSameAs(exception);

        verify(blockParser, never()).parse(any());
        verify(checkpointService, never()).advance(any(), anyLong(), anyLong(), any());
        assertThat(scanService.scanBlocks()).isEqualTo(1);
        verify(nodeManager, times(2)).getBlockDataByHeight(100L);
        verify(depositPublisher, never()).publishAndWait(any(), anyList());
    }

    @Test
    void shouldRetrySameBlockOnNextRunWhenBlockParsingFails() {
        BizException exception = BizException.of(ScannerBizErrCode.TRON_TRANSACTION_INVALID);
        TronBlockData blockData = blockData(100L);
        when(checkpointService.findByNetwork("MAINNET"))
            .thenReturn(checkpoint(99L, "block-99"));
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData);
        when(blockParser.parse(blockData))
            .thenThrow(exception)
            .thenReturn(List.of());
        when(checkpointService.advance("MAINNET", 99L, 100L, "block-100"))
            .thenReturn(true);

        assertThatThrownBy(scanService::scanBlocks).isSameAs(exception);

        verify(depositPublisher, never()).publishAndWait(any(), anyList());
        verify(checkpointService, never()).advance(any(), anyLong(), anyLong(), any());
        assertThat(scanService.scanBlocks()).isEqualTo(1);
        verify(nodeManager, times(2)).getBlockDataByHeight(100L);
        verify(blockParser, times(2)).parse(blockData);
    }

    @Test
    void shouldResendSameBlockWhenCheckpointAdvanceFails() {
        TronScanCheckpoint checkpoint = checkpoint(99L, "block-99");
        TronBlockData blockData = blockData(100L);
        List<TronDepositEvent> deposits = List.of(deposit(100L));
        when(checkpointService.findByNetwork("MAINNET")).thenReturn(checkpoint);
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(100L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData);
        when(blockParser.parse(blockData)).thenReturn(deposits);
        when(checkpointService.advance("MAINNET", 99L, 100L, "block-100"))
            .thenThrow(new DataAccessResourceFailureException("checkpoint update failed"))
            .thenReturn(true);

        assertThatThrownBy(scanService::scanBlocks)
            .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(scanService.scanBlocks()).isEqualTo(1);

        verify(depositPublisher, times(2)).publishAndWait(blockData, deposits);
        verify(checkpointService, times(2)).advance("MAINNET", 99L, 100L, "block-100");
    }

    @Test
    void shouldReloadLatestCheckpointAfterConcurrentAdvance() {
        when(checkpointService.findByNetwork("MAINNET"))
            .thenReturn(checkpoint(99L, "block-99"), checkpoint(100L, "block-100"));
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(101L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData(100L));
        when(nodeManager.getBlockDataByHeight(101L)).thenReturn(blockData(101L));
        when(blockParser.parse(any(TronBlockData.class))).thenReturn(List.of());
        when(checkpointService.advance("MAINNET", 99L, 100L, "block-100"))
            .thenReturn(false);
        when(checkpointService.advance("MAINNET", 100L, 101L, "block-101"))
            .thenReturn(true);

        assertThatThrownBy(scanService::scanBlocks)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_CONFLICT);
        verify(nodeManager, never()).getBlockDataByHeight(101L);

        assertThat(scanService.scanBlocks()).isEqualTo(1);
        verify(nodeManager).getBlockDataByHeight(100L);
        verify(nodeManager).getBlockDataByHeight(101L);
    }

    @Test
    void shouldResumeAfterLastSuccessfulBlockWhenLaterBlockFails() {
        RuntimeException failure = new RuntimeException("publish failed");
        TronBlockData block100 = blockData(100L);
        TronBlockData block101 = blockData(101L);
        List<TronDepositEvent> deposits = List.of(deposit(101L));
        when(checkpointService.findByNetwork("MAINNET"))
            .thenReturn(checkpoint(99L, "block-99"), checkpoint(100L, "block-100"));
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(102L), nodeHeight(101L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(block100);
        when(nodeManager.getBlockDataByHeight(101L)).thenReturn(block101);
        when(blockParser.parse(block100)).thenReturn(List.of());
        when(blockParser.parse(block101)).thenReturn(deposits);
        doThrow(failure).doNothing().when(depositPublisher).publishAndWait(block101, deposits);
        when(checkpointService.advance("MAINNET", 99L, 100L, "block-100"))
            .thenReturn(true);
        when(checkpointService.advance("MAINNET", 100L, 101L, "block-101"))
            .thenReturn(true);

        assertThatThrownBy(scanService::scanBlocks).isSameAs(failure);
        verify(checkpointService).advance("MAINNET", 99L, 100L, "block-100");
        verify(checkpointService, never()).advance("MAINNET", 100L, 101L, "block-101");
        verify(nodeManager, never()).getBlockDataByHeight(102L);

        assertThat(scanService.scanBlocks()).isEqualTo(1);
        verify(nodeManager).getBlockDataByHeight(100L);
        verify(nodeManager, times(2)).getBlockDataByHeight(101L);
        verify(checkpointService).advance("MAINNET", 100L, 101L, "block-101");
    }

    @Test
    void shouldInitializeCheckpointBeforeConfiguredStartHeight() {
        scannerProperties.setStartBlockHeight(100L);
        when(checkpointService.findByNetwork("MAINNET")).thenReturn(null);
        when(nodeManager.getBlockHeaderByHeight(99L)).thenReturn(nodeHeight(99L));
        when(checkpointService.initializeIfAbsent(any(TronScanCheckpoint.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(99L));

        assertThat(scanService.scanBlocks()).isZero();

        ArgumentCaptor<TronScanCheckpoint> checkpointCaptor = ArgumentCaptor.captor();
        verify(checkpointService).initializeIfAbsent(checkpointCaptor.capture());
        assertThat(checkpointCaptor.getValue().getLastBlockNumber()).isEqualTo(99L);
        assertThat(checkpointCaptor.getValue().getLastBlockHash()).isEqualTo("block-99");
    }

    @Test
    void shouldStopAtConfiguredBlockLimit() {
        scannerProperties.setMaxBlocksPerRun(2);
        when(checkpointService.findByNetwork("MAINNET"))
            .thenReturn(checkpoint(99L, "block-99"));
        when(nodeManager.getHeadHeight()).thenReturn(nodeHeight(102L));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(blockData(100L));
        when(nodeManager.getBlockDataByHeight(101L)).thenReturn(blockData(101L));
        when(blockParser.parse(any(TronBlockData.class))).thenReturn(List.of());
        when(checkpointService.advance(any(), anyLong(), anyLong(), any()))
            .thenReturn(true);

        assertThat(scanService.scanBlocks()).isEqualTo(2);

        verify(nodeManager, never()).getBlockDataByHeight(102L);
    }

    private TronScanCheckpoint checkpoint(long blockHeight, String blockHash) {
        return new TronScanCheckpoint()
            .setChainNetwork("MAINNET")
            .setLastBlockNumber(blockHeight)
            .setLastBlockHash(blockHash);
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
            "TRON",
            "MAINNET",
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
