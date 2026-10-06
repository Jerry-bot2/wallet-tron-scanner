package com.nb.tron.scanner.support;

import com.nb.tron.scanner.entity.TronScanCheckpoint;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.TimeUnit;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * 一轮扫块的统计作用域：入口统一开启，节点、发送、进度服务各自在自己的入口采集。
 * <p>
 * 1. 当前扫描线程内的调用归入本轮，不在业务流程中传递统计参数。<br>
 * 2. 其他线程的节点健康检查，以及统计范围外的调用，不计入本轮。<br>
 * 3. 无论成功还是失败，finally 都先清理作用域，再输出一次汇总；异常继续向外抛出。</p>
 * <p>
 * Author: bin jack
 * Date: 06.10.26
 */
@Slf4j
public final class HeadScanStatistics {

    private static final ThreadLocal<HeadScanStatistics> CURRENT = new ThreadLocal<>();

    private final String chainNetwork;
    private final long startedAt = System.nanoTime();

    private Long startHeight;
    private Long lastCompletedHeight;
    private Long observedHeadHeight;
    private int scannedCount;
    private long nodeReadNanos;
    private long kafkaAckNanos;
    private long progressCommitNanos;
    private boolean forkRecheck;
    private boolean completed;

    private HeadScanStatistics(String chainNetwork) {
        this.chainNetwork = chainNetwork;
    }

    /**
     * 开启一轮统计，执行业务流程，结束后统一清理和汇总；不改变返回值或异常。
     */
    public static int recordRound(String chainNetwork, IntSupplier action) {
        HeadScanStatistics previous = CURRENT.get();
        HeadScanStatistics statistics = new HeadScanStatistics(chainNetwork);
        CURRENT.set(statistics);
        try {
            int result = action.getAsInt();
            statistics.completed = true;
            return result;
        } finally {
            // XXL-JOB 会复用线程，结束后必须清理；嵌套调用则恢复外层作用域。
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            statistics.logSummary();
        }
    }

    /**
     * 复用本轮已读到的节点高度，例如 1200，不为统计额外查询节点。
     */
    public static void observeHead(long headHeight) {
        HeadScanStatistics statistics = CURRENT.get();
        if (statistics != null) {
            statistics.observedHeadHeight = headHeight;
        }
    }

    /**
     * 进度服务加载检查点后，记录本轮起点，例如 1000；不额外查询数据库。
     */
    public static TronScanCheckpoint recordCheckpointLoad(Supplier<TronScanCheckpoint> action) {
        TronScanCheckpoint checkpoint = action.get();
        HeadScanStatistics statistics = CURRENT.get();
        if (statistics != null) {
            statistics.startHeight = checkpoint.getLastBlockNumber();
            statistics.lastCompletedHeight = checkpoint.getLastBlockNumber();
        }
        return checkpoint;
    }

    /**
     * 进入分叉复查；父 Hash 接不上也需要复查，不代表已经确认发生分叉。
     */
    public static void markForkRecheck() {
        HeadScanStatistics statistics = CURRENT.get();
        if (statistics != null) {
            statistics.forkRecheck = true;
        }
    }

    /**
     * 在 HTTP 入口累计本轮所有节点请求耗时，包含失败请求和分叉查找；范围外直接执行。
     */
    public static <T> T timeNodeRead(Supplier<T> action) {
        HeadScanStatistics statistics = CURRENT.get();
        if (statistics == null) {
            return action.get();
        }
        long started = System.nanoTime();
        try {
            return action.get();
        } finally {
            statistics.nodeReadNanos += System.nanoTime() - started;
        }
    }

    /**
     * 累加 Kafka 发送到收到 ACK 的耗时；超时或发送失败也计时。
     */
    public static void timeKafkaAck(Runnable action) {
        HeadScanStatistics statistics = CURRENT.get();
        if (statistics == null) {
            action.run();
            return;
        }
        long started = System.nanoTime();
        try {
            action.run();
        } finally {
            statistics.kafkaAckNanos += System.nanoTime() - started;
        }
    }

    /**
     * 累加进度事务耗时；只有成功提交后才更新完成高度和数量，失败不计为完成。
     */
    public static TronScanCheckpoint timeProgressCommit(Supplier<TronScanCheckpoint> action) {
        HeadScanStatistics statistics = CURRENT.get();
        if (statistics == null) {
            return action.get();
        }
        long started = System.nanoTime();
        try {
            TronScanCheckpoint checkpoint = action.get();
            statistics.lastCompletedHeight = checkpoint.getLastBlockNumber();
            statistics.scannedCount++;
            return checkpoint;
        } finally {
            statistics.progressCommitNanos += System.nanoTime() - started;
        }
    }

    /**
     * 例如本轮 1000 → 1100、读到节点高度 1200：完成 100 块，尚落后 100 块。
     * <p>落后数量使用本轮读取时的节点高度。分叉轮次可能已回退，落后数量记为 null，
     * 回退位置查看分叉日志；lastCompletedHeight 仍表示本轮回退前最后成功提交的位置。</p>
     * <p>总耗时还包含初始化、解析和分叉处理；阶段耗时不要求相加等于总耗时。
     * 没完成区块时平均耗时为 null；异常继续由原调用链处理，本类不打印异常堆栈。</p>
     */
    private void logSummary() {
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        Long averageBlockMillis = scannedCount == 0 ? null : elapsedMillis / scannedCount;
        Long remainingBlocks = forkRecheck || observedHeadHeight == null || lastCompletedHeight == null
            ? null : Math.max(observedHeadHeight - lastCompletedHeight, 0L);

        log.info("TRON Head扫描本轮结束，chainNetwork={}，completed={}，forkRecheck={}，startHeight={}，lastCompletedHeight={}，observedHeadHeight={}，remainingBlocks={}，scannedCount={}，elapsedMillis={}，averageBlockMillis={}，nodeReadMillis={}，kafkaAckMillis={}，progressCommitMillis={}",
            chainNetwork, completed, forkRecheck, startHeight, lastCompletedHeight, observedHeadHeight,
            remainingBlocks, scannedCount, elapsedMillis, averageBlockMillis,
            TimeUnit.NANOSECONDS.toMillis(nodeReadNanos),
            TimeUnit.NANOSECONDS.toMillis(kafkaAckNanos),
            TimeUnit.NANOSECONDS.toMillis(progressCommitNanos));
    }
}
