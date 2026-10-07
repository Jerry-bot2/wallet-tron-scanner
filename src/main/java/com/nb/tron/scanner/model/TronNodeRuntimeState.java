package com.nb.tron.scanner.model;

import com.nb.tron.scanner.enums.TronNodeHealthStatus;
import com.nb.tron.scanner.enums.TronNodeRole;

import java.time.Instant;

/**
 * TRON 节点运行状态
 *
 * @param nodeCode                节点编码
 * @param nodeRole                节点角色
 * @param healthStatus            健康状态
 * @param latestBlockHeight       最近一次成功读取的区块高度，尚未成功时为空
 * @param consecutiveFailureCount 连续失败次数
 * @param responseTimeMillis      最近一次健康检查耗时
 * @param lastSuccessAt           最近一次检查成功时间，尚未成功时为空
 * @param healthySince            当前连续健康周期的开始时间，节点恢复时重新计算
 * @param heightReadStartedAt     最近一次成功高度读取的开始时间（System.nanoTime），用于排除晚返回的旧结果
 *                                Author: bin jack
 *                                Date: 03.10.26
 */
public record TronNodeRuntimeState(String nodeCode,
                                   TronNodeRole nodeRole,
                                   TronNodeHealthStatus healthStatus,
                                   Long latestBlockHeight,
                                   int consecutiveFailureCount,
                                   long responseTimeMillis,
                                   Instant lastSuccessAt,
                                   Instant healthySince,
                                   long heightReadStartedAt) {

    /**
     * 使用本次成功结果覆盖节点状态。
     */
    public static TronNodeRuntimeState success(String nodeCode,
                                               TronNodeRole nodeRole,
                                               TronNodeRuntimeState currentState,
                                               long blockHeight,
                                               long responseTimeMillis,
                                               Instant successAt,
                                               long readStartedAt) {
        // 1. 检查成功：更新健康状态、耗时和成功时间，失败次数清零。
        TronNodeRuntimeState healthyState = new TronNodeRuntimeState(
            nodeCode,
            nodeRole,
            TronNodeHealthStatus.HEALTHY,
            currentState == null ? null : currentState.latestBlockHeight(),
            0,
            responseTimeMillis,
            successAt,
            resolveHealthySince(currentState, successAt),
            currentState == null ? 0L : currentState.heightReadStartedAt());

        // 2. 高度按同一个规则更新：旧请求晚返回时，保留扫描查询已经写入的新高度。
        return healthyState.withLatestHeight(blockHeight, readStartedAt);
    }

    /**
     * 更新节点高度；较早发起的请求即使后返回，也不能覆盖较新请求的结果。
     * 高度按本次结果更新，允许节点发生重组后高度降低。
     */
    public TronNodeRuntimeState withLatestHeight(long blockHeight, long readStartedAt) {
        if (latestBlockHeight != null && readStartedAt - heightReadStartedAt < 0) {
            return this;
        }
        return new TronNodeRuntimeState(nodeCode, nodeRole, healthStatus, blockHeight,
            consecutiveFailureCount, responseTimeMillis, lastSuccessAt, healthySince, readStartedAt);
    }

    /**
     * 累加一次失败；连续失败达到阈值后将节点标记为不可用。
     */
    public static TronNodeRuntimeState failure(String nodeCode,
                                               TronNodeRole nodeRole,
                                               TronNodeRuntimeState currentState,
                                               long responseTimeMillis,
                                               int failureThreshold) {
        int failureCount = currentState == null ? 1 : currentState.consecutiveFailureCount() + 1;
        TronNodeHealthStatus healthStatus = resolveFailureStatus(currentState, failureCount, failureThreshold);

        return new TronNodeRuntimeState(
            nodeCode,
            nodeRole,
            healthStatus,
            currentState == null ? null : currentState.latestBlockHeight(),
            failureCount,
            responseTimeMillis,
            currentState == null ? null : currentState.lastSuccessAt(),
            preserveHealthySince(currentState, healthStatus),
            currentState == null ? 0L : currentState.heightReadStartedAt());
    }

    public boolean isHealthy() {
        return healthStatus == TronNodeHealthStatus.HEALTHY;
    }

    private static TronNodeHealthStatus resolveFailureStatus(TronNodeRuntimeState currentState,
                                                             int failureCount,
                                                             int failureThreshold) {
        if (failureCount >= failureThreshold) {
            return TronNodeHealthStatus.UNHEALTHY;
        }
        return currentState == null ? TronNodeHealthStatus.UNKNOWN : currentState.healthStatus();
    }

    private static Instant resolveHealthySince(TronNodeRuntimeState currentState, Instant successAt) {
        if (currentState == null || !currentState.isHealthy()) {
            return successAt;
        }
        return currentState.healthySince();
    }

    private static Instant preserveHealthySince(TronNodeRuntimeState currentState,
                                                TronNodeHealthStatus healthStatus) {
        if (currentState == null || healthStatus != TronNodeHealthStatus.HEALTHY) {
            return null;
        }
        return currentState.healthySince();
    }
}
