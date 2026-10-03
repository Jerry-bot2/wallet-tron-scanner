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
 *                                Author: bin jack
 *                                Date: 03.10.26
 */
public record TronNodeRuntimeState(String nodeCode,
                                   TronNodeRole nodeRole,
                                   TronNodeHealthStatus healthStatus,
                                   Long latestBlockHeight,
                                   int consecutiveFailureCount,
                                   long responseTimeMillis,
                                   Instant lastSuccessAt) {

    /**
     * 使用本次成功结果覆盖节点状态。
     */
    public static TronNodeRuntimeState success(String nodeCode,
                                               TronNodeRole nodeRole,
                                               long blockHeight,
                                               long responseTimeMillis,
                                               Instant successAt) {
        return new TronNodeRuntimeState(
            nodeCode,
            nodeRole,
            TronNodeHealthStatus.HEALTHY,
            blockHeight,
            0,
            responseTimeMillis,
            successAt);
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
            currentState == null ? null : currentState.lastSuccessAt());
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
}
