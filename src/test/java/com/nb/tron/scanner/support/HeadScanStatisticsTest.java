package com.nb.tron.scanner.support;

import com.nb.tron.scanner.entity.TronScanCheckpoint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Author: bin jack
 * Date: 06.10.26
 */
@ExtendWith(OutputCaptureExtension.class)
class HeadScanStatisticsTest {

    @Test
    void shouldExcludeBackgroundHealthChecksFromCurrentRound(CapturedOutput output) {
        try (var executor = Executors.newSingleThreadExecutor()) {
            int result = HeadScanStatistics.recordRound("NILE", () -> {
                HeadScanStatistics.recordCheckpointLoad(() -> checkpoint(100));
                HeadScanStatistics.observeScanBoundary(105);

                // 其他线程即使调用相同入口，也不能改写扫描线程的高度和完成块数。
                CompletableFuture.runAsync(() -> {
                    HeadScanStatistics.timeNodeRead(() -> {
                        HeadScanStatistics.observeScanBoundary(10000);
                        return 10000;
                    });
                    HeadScanStatistics.recordCheckpointLoad(() -> checkpoint(9000));
                    HeadScanStatistics.timeProgressCommit(() -> checkpoint(9001));
                }, executor).join();

                HeadScanStatistics.timeProgressCommit(() -> checkpoint(101));
                return 1;
            });

            assertThat(result).isEqualTo(1);
        }

        assertThat(output).contains("completed=true，startHeight=100，lastCompletedHeight=101，scanBoundaryHeight=105，remainingBlocks=4，scannedCount=1");
        assertThat(output.getOut().lines().filter(line -> line.contains("TRON固化扫描本轮结束")).count()).isEqualTo(1L);
    }

    @Test
    void shouldRestoreOuterRoundAfterInnerFailure(CapturedOutput output) {
        RuntimeException failure = new IllegalStateException("commit failed");
        int result = HeadScanStatistics.recordRound("NILE", () -> {
            HeadScanStatistics.recordCheckpointLoad(() -> checkpoint(100));
            HeadScanStatistics.observeScanBoundary(105);
            HeadScanStatistics.timeProgressCommit(() -> checkpoint(101));

            assertThatThrownBy(() -> HeadScanStatistics.recordRound("NILE", () -> {
                HeadScanStatistics.recordCheckpointLoad(() -> checkpoint(1000));
                HeadScanStatistics.observeScanBoundary(1002);
                HeadScanStatistics.timeProgressCommit(() -> { throw failure; });
                return 0;
            })).isSameAs(failure);

            HeadScanStatistics.timeProgressCommit(() -> checkpoint(102));
            return 2;
        });

        assertThat(result).isEqualTo(2);
        assertThat(output).contains("completed=false，startHeight=1000，lastCompletedHeight=1000，scanBoundaryHeight=1002，remainingBlocks=2，scannedCount=0");
        assertThat(output).contains("completed=true，startHeight=100，lastCompletedHeight=102，scanBoundaryHeight=105，remainingBlocks=3，scannedCount=2");
    }

    private TronScanCheckpoint checkpoint(long height) {
        return new TronScanCheckpoint().setChainNetwork("NILE")
            .setLastBlockNumber(height).setLastBlockHash("h" + height);
    }
}
