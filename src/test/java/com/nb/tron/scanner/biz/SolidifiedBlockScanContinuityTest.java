package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.mq.publisher.DepositConfirmedPublisher;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronNodeHeight;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 验证TRON固化检查点和新区块连续性异常时停止扫描，不自动回退进度。
 * <p>
 * Author: bin jack
 * Date: 22.10.26
 */
@ExtendWith(OutputCaptureExtension.class)
class SolidifiedBlockScanContinuityTest {

    private final TronScannerProperties properties = new TronScannerProperties();

    private TronNodeManager nodeManager;

    private DepositDiscoveryService discoveryService;

    private DepositConfirmedPublisher depositPublisher;

    private HeadScanProgressService progressService;

    private HeadBlockScanService scanService;

    @BeforeEach
    void setUp() {
        TronAddressIndex addressIndex = mock(TronAddressIndex.class);
        TronCurrencyIndex currencyIndex = mock(TronCurrencyIndex.class);
        nodeManager = mock(TronNodeManager.class);
        discoveryService = mock(DepositDiscoveryService.class);
        depositPublisher = mock(DepositConfirmedPublisher.class);
        progressService = mock(HeadScanProgressService.class);
        when(addressIndex.isReady()).thenReturn(true);
        when(currencyIndex.isReady()).thenReturn(true);
        when(discoveryService.discover(any())).thenReturn(List.of());

        HeadBlockContinuityService continuityService = new HeadBlockContinuityService(
            nodeManager, mock(HeadBlockAncestorFinder.class), progressService);
        scanService = new HeadBlockScanService(
            properties, addressIndex, currencyIndex, nodeManager,
            discoveryService, depositPublisher, continuityService, progressService);
    }

    @Test
    void shouldContinueWhenSolidifiedCheckpointMatches() {
        TronScanCheckpoint checkpoint = checkpoint(100L, "block-100");
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        when(nodeManager.getBlockHeaderByHeight(100L)).thenReturn(height(100L, "block-100"));
        when(nodeManager.getSolidHeight()).thenReturn(height(100L, "block-100"));

        assertThat(scanService.scanBlocks()).isZero();

        verify(nodeManager).getBlockHeaderByHeight(100L);
        verify(nodeManager).getSolidHeight();
        verify(progressService, never()).rewind(any(), any());
    }

    @Test
    void shouldStopAndLogWhenSolidifiedCheckpointHashChanges(CapturedOutput output) {
        TronScanCheckpoint checkpoint = checkpoint(100L, "local-block-100");
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        when(nodeManager.getBlockHeaderByHeight(100L)).thenReturn(height(100L, "chain-block-100"));

        assertThatThrownBy(scanService::scanBlocks)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.SOLIDIFIED_SCAN_CHAIN_INVALID);

        assertThat(output).contains(
            "TRON固化检查点Hash不一致",
            "errorCode=916003",
            "blockNumber=100",
            "checkpointHash=local-block-100",
            "chainHash=chain-block-100");
        verify(nodeManager, never()).getSolidHeight();
        verifyNoInteractions(discoveryService, depositPublisher);
        verify(progressService, never()).advance(any(), any());
        verify(progressService, never()).rewind(any(), any());
    }

    @Test
    void shouldStopAndLogWhenSolidifiedBlockParentHashIsNotContinuous(CapturedOutput output) {
        TronScanCheckpoint checkpoint = checkpoint(100L, "block-100");
        TronBlockData block = block(101L, "wrong-parent");
        prepareNextBlock(checkpoint, block);

        assertThatThrownBy(scanService::scanBlocks)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.SOLIDIFIED_SCAN_CHAIN_INVALID);

        assertThat(output).contains(
            "TRON固化区块不连续",
            "errorCode=916003",
            "expectedBlockNumber=101",
            "actualBlockNumber=101",
            "expectedParentHash=block-100",
            "actualParentHash=wrong-parent");
        verifyNoInteractions(discoveryService, depositPublisher);
        verify(progressService, never()).advance(any(), any());
        verify(progressService, never()).rewind(any(), any());
    }

    @Test
    void shouldStopBeforeParsingWhenNodeReturnsWrongBlockHeight() {
        TronScanCheckpoint checkpoint = checkpoint(100L, "block-100");
        prepareNextBlock(checkpoint, block(102L, "block-100"));

        assertThatThrownBy(scanService::scanBlocks)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.SOLIDIFIED_SCAN_CHAIN_INVALID);

        verifyNoInteractions(discoveryService, depositPublisher);
        verify(progressService, never()).advance(any(), any());
    }

    @Test
    void shouldProcessConnectedSolidifiedBlock() {
        TronScanCheckpoint checkpoint = checkpoint(100L, "block-100");
        TronBlockData block = block(101L, "block-100");
        prepareNextBlock(checkpoint, block);
        when(progressService.advance(checkpoint, block)).thenReturn(checkpoint(101L, "block-101"));

        assertThat(scanService.scanBlocks()).isEqualTo(1);

        verify(discoveryService).discover(block);
        verify(progressService).advance(checkpoint, block);
        verify(progressService, never()).rewind(any(), any());
    }

    @Test
    void shouldScanGenesisWithoutReadingNegativeCheckpointHeight() {
        TronScanCheckpoint checkpoint = checkpoint(-1L, "");
        TronBlockData genesis = block(0L, "");
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        when(nodeManager.getSolidHeight()).thenReturn(height(0L, "block-0"));
        when(nodeManager.getBlockDataByHeight(0L)).thenReturn(genesis);
        when(progressService.advance(checkpoint, genesis)).thenReturn(checkpoint(0L, "block-0"));

        assertThat(scanService.scanBlocks()).isEqualTo(1);

        verify(nodeManager, never()).getBlockHeaderByHeight(-1L);
        verify(progressService, never()).rewind(any(), any());
    }

    @Test
    void shouldKeepProgressWhenCheckpointHeaderReadFails() {
        BizException failure = BizException.of(ScannerBizErrCode.TRON_SDK_CALL_FAILED);
        when(progressService.loadCheckpoint()).thenReturn(checkpoint(100L, "block-100"));
        when(nodeManager.getBlockHeaderByHeight(100L)).thenThrow(failure);

        assertThatThrownBy(scanService::scanBlocks).isSameAs(failure);

        verify(nodeManager, never()).getSolidHeight();
        verifyNoInteractions(discoveryService, depositPublisher);
        verify(progressService, never()).advance(any(), any());
        verify(progressService, never()).rewind(any(), any());
    }

    private void prepareNextBlock(TronScanCheckpoint checkpoint, TronBlockData block) {
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        when(nodeManager.getBlockHeaderByHeight(checkpoint.getLastBlockNumber()))
            .thenReturn(height(checkpoint.getLastBlockNumber(), checkpoint.getLastBlockHash()));
        when(nodeManager.getSolidHeight()).thenReturn(height(101L, "block-101"));
        when(nodeManager.getBlockDataByHeight(101L)).thenReturn(block);
    }

    private TronScanCheckpoint checkpoint(long blockNumber, String blockHash) {
        return new TronScanCheckpoint()
            .setChainNetwork("MAINNET")
            .setLastBlockNumber(blockNumber)
            .setLastBlockHash(blockHash);
    }

    private TronNodeHeight height(long blockNumber, String blockHash) {
        return new TronNodeHeight("full-primary", blockNumber, blockHash, Instant.EPOCH);
    }

    private TronBlockData block(long blockNumber, String parentBlockHash) {
        return new TronBlockData(
            "full-primary",
            blockNumber,
            "block-" + blockNumber,
            parentBlockHash,
            Instant.EPOCH,
            List.of(),
            Map.of());
    }
}
