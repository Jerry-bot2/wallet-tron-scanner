package com.nb.tron.scanner.node;

import com.nb.tron.sdk.node.TronNodePool;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 定时触发SDK节点池健康检查。
 *
 * <p>网络核对、失败计数、高度快照和健康状态全部由SDK维护；本类只负责Scanner任务调度。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Service
@RequiredArgsConstructor
public class TronNodeHealthService {

    private final TronNodePool nodePool;

    @Scheduled(
        initialDelayString = "${nb.tron.scanner.node.health-check-interval:15s}",
        fixedDelayString = "${nb.tron.scanner.node.health-check-interval:15s}")
    public void refreshNodeStates() {
        nodePool.refresh();
    }
}
