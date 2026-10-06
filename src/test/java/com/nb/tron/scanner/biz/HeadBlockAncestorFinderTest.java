package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.node.TronBlockHeaderReader;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.service.ITronScannedBlockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Author: bin jack
 * Date: 05.10.26
 */
class HeadBlockAncestorFinderTest {

    private TronNodeManager nodeManager;
    private TronBlockHeaderReader reader;
    private ITronScannedBlockService scannedBlockService;
    private HeadBlockAncestorFinder finder;

    @BeforeEach
    void setUp() {
        nodeManager = mock(TronNodeManager.class);
        reader = mock(TronBlockHeaderReader.class);
        scannedBlockService = mock(ITronScannedBlockService.class);
        finder = new HeadBlockAncestorFinder(nodeManager, scannedBlockService);
        when(nodeManager.openBlockHeaderReader(anyLong())).thenReturn(reader);
    }

    @Test
    void shouldOnlyReadLastHeaderWhenCheckpointStillMatches() {
        when(reader.getBlockHeaderByHeight(999)).thenReturn(header(999, "h999"));
        assertThat(finder.findCommonAncestor(checkpoint(999)).getBlockNumber()).isEqualTo(999);
        verify(reader, times(1)).getBlockHeaderByHeight(999);
        verifyNoInteractions(scannedBlockService);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1, 127, 499, 500, 997, 998})
    void shouldFindNearestCommonBlockWithLogarithmicReads(long commonHeight) {
        history(0, 999, commonHeight);
        TronScannedBlock common = finder.findCommonAncestor(checkpoint(999));

        assertThat(common.getBlockNumber()).isEqualTo(commonHeight);
        assertThat(common.getBlockHash()).isEqualTo("h" + commonHeight);
        // 1000 条历史：二分至多 10 次，另加首尾和边界核验。
        assertThat(mockingDetails(reader).getInvocations().size()).isLessThanOrEqualTo(16);
        verify(nodeManager, times(1)).openBlockHeaderReader(999);
        verifyNoMoreInteractions(nodeManager);
    }

    @Test
    void shouldFindEveryForkBoundaryInThousandBlockWindow() {
        long oldest = 71_560_000;
        long last = oldest + 999;
        AtomicLong commonHeight = new AtomicLong(oldest);
        history(oldest, last, oldest);
        when(reader.getBlockHeaderByHeight(anyLong())).thenAnswer(invocation -> {
            long height = invocation.getArgument(0);
            return header(height, (height <= commonHeight.get() ? "h" : "new") + height);
        });

        // 穷举 1000 条历史内的 999 个分叉边界，覆盖只换末块和退到最早一块。
        for (long height = oldest; height < last; height++) {
            commonHeight.set(height);
            clearInvocations(reader);
            TronScannedBlock common = finder.findCommonAncestor(checkpoint(last));
            assertThat(common.getBlockNumber()).as("共同区块高度 %s", height).isEqualTo(height);
            assertThat(mockingDetails(reader).getInvocations().size()).isLessThanOrEqualTo(16);
        }
    }

    @Test
    void shouldSupportGenesisSentinelWithoutNegativeRpc() {
        history(-1, 5, -1);
        assertThat(finder.findCommonAncestor(checkpoint(5)).getBlockNumber()).isEqualTo(-1);
        verify(reader, never()).getBlockHeaderByHeight(-1);
    }

    @Test
    void shouldStopWhenOldestRetainedBlockAlsoDiffers() {
        history(100, 110, 99);
        assertThat(finder.findCommonAncestor(checkpoint(110))).isNull();
        verify(scannedBlockService, never()).findByHeight(eq("MAINNET"), anyLong());
    }

    @Test
    void shouldRejectMissingHistoryInsteadOfInventingBoundary() {
        when(reader.getBlockHeaderByHeight(110)).thenReturn(header(110, "new110"));
        assertError(110, ScannerBizErrCode.HEAD_SCAN_HISTORY_INVALID);
    }

    @Test
    void shouldStopWhenHistoryLookupFails() {
        history(100, 110, 106);
        when(scannedBlockService.findByHeight("MAINNET", 105)).thenReturn(null);
        assertError(110, ScannerBizErrCode.HEAD_SCAN_HISTORY_INVALID);
    }

    @ParameterizedTest
    @ValueSource(longs = {10_002, 10_003, 20_000, 30_000})
    void shouldFindCommonBlockInTwentyThousandBlockWindow(long actualCommonHeight) {
        history(10_002, 30_001, actualCommonHeight);

        TronScannedBlock common = finder.findCommonAncestor(checkpoint(30_001));

        assertThat(common.getBlockNumber()).isEqualTo(actualCommonHeight);
        assertThat(mockingDetails(reader).getInvocations().size()).isLessThanOrEqualTo(24);
    }

    @Test
    void shouldPropagateRpcFailureWithoutTreatingItAsHashMismatch() {
        history(100, 110, 106);
        when(reader.getBlockHeaderByHeight(105)).thenThrow(BizException.of(ScannerBizErrCode.TRON_NODE_TIMEOUT));
        assertError(110, ScannerBizErrCode.TRON_NODE_TIMEOUT);
        verify(reader, never()).getBlockHeaderByHeight(102);
    }

    @Test
    void shouldStopIfTipChangesDuringSearch() {
        history(100, 110, 106);
        when(reader.getBlockHeaderByHeight(110)).thenReturn(header(110, "new110"), header(110, "other110"));
        assertError(110, ScannerBizErrCode.HEAD_SCAN_CHAIN_CHANGED);
    }

    @Test
    void shouldRecheckCommonBlockBeforeReturning() {
        history(100, 101, 100);
        when(reader.getBlockHeaderByHeight(100)).thenReturn(header(100, "h100"), header(100, "changed100"));
        assertError(101, ScannerBizErrCode.HEAD_SCAN_CHAIN_CHANGED);
    }

    @Test
    void shouldRecheckFirstDifferentBlockBeforeReturning() {
        history(100, 102, 100);
        when(reader.getBlockHeaderByHeight(101)).thenReturn(header(101, "new101"), header(101, "h101"));
        assertError(102, ScannerBizErrCode.HEAD_SCAN_CHAIN_CHANGED);
    }

    private void history(long oldest, long last, long commonHeight) {
        when(scannedBlockService.findOldestBlock("MAINNET")).thenReturn(summary(oldest));
        when(scannedBlockService.findByHeight(eq("MAINNET"), anyLong())).thenAnswer(invocation -> {
            long height = invocation.getArgument(1);
            return height >= oldest && height <= last ? summary(height) : null;
        });
        when(reader.getBlockHeaderByHeight(anyLong())).thenAnswer(invocation -> {
            long height = invocation.getArgument(0);
            return header(height, (height <= commonHeight ? "h" : "new") + height);
        });
    }

    private void assertError(long lastHeight, ScannerBizErrCode errorCode) {
        assertThatThrownBy(() -> finder.findCommonAncestor(checkpoint(lastHeight)))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode()).isEqualTo(errorCode);
    }

    private TronScanCheckpoint checkpoint(long height) {
        return new TronScanCheckpoint().setChainNetwork("MAINNET")
            .setLastBlockNumber(height).setLastBlockHash("h" + height);
    }

    private TronScannedBlock summary(long height) {
        return new TronScannedBlock().setChainNetwork("MAINNET").setBlockNumber(height)
            .setBlockHash(height < 0 ? "" : "h" + height);
    }

    private TronNodeHeight header(long height, String hash) {
        return new TronNodeHeight("full", height, hash, Instant.EPOCH);
    }
}
