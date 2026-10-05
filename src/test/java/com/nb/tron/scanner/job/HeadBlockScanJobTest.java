package com.nb.tron.scanner.job;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.biz.HeadBlockScanService;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 05.10.26
 */
class HeadBlockScanJobTest {

    private final HeadBlockScanService scanService = mock(HeadBlockScanService.class);
    private final HeadBlockScanJob scanJob = new HeadBlockScanJob(scanService);

    @Test
    void shouldReturnScannedCountAfterNormalRound() {
        when(scanService.scanBlocks()).thenReturn(3);

        assertThat(scanJob.doExecute(null)).isEqualTo(3);
    }

    @Test
    void shouldFinishRoundNormallyAfterSuccessfulRewind() {
        when(scanService.scanBlocks()).thenThrow(
            BizException.of(ScannerBizErrCode.HEAD_SCAN_FORK_DETECTED, 1010, 1008));

        assertThat(scanJob.doExecute(null)).isZero();
    }

    @Test
    void shouldPropagateCheckpointFailure() {
        BizException failure = BizException.of(ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_CONFLICT);
        when(scanService.scanBlocks()).thenThrow(failure);

        assertThatThrownBy(() -> scanJob.doExecute(null)).isSameAs(failure);
    }

    @Test
    void shouldPropagateUnexpectedFailure() {
        RuntimeException failure = new RuntimeException("database unavailable");
        when(scanService.scanBlocks()).thenThrow(failure);

        assertThatThrownBy(() -> scanJob.doExecute(null)).isSameAs(failure);
    }
}
