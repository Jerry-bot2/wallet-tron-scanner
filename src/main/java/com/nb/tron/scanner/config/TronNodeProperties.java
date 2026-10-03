package com.nb.tron.scanner.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.util.unit.DataSize;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * TRON 节点访问策略
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Getter
@Setter
public class TronNodeProperties {

    /**
     * 建立节点连接的最长等待时间
     */
    private Duration connectTimeout = Duration.ofSeconds(3);

    /**
     * 等待节点返回完整响应的最长时间
     */
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * 单次节点响应允许占用的最大内存
     */
    private DataSize maxResponseSize = DataSize.ofMegabytes(16);

    /**
     * 每个 Scanner 实例刷新本机节点状态的间隔
     */
    private Duration healthCheckInterval = Duration.ofSeconds(15);

    /**
     * 节点连续失败达到该次数后标记为不健康
     */
    private int failureThreshold = 3;

    /**
     * FullNode 低于健康节点最高高度达到该数量时视为明显落后。
     */
    private int heightLagThreshold = 20;

    /**
     * 原主节点恢复后重新参与主节点选择前的稳定观察时间
     */
    private Duration recoveryCooldown = Duration.ofSeconds(60);

    /**
     * 当前环境可使用的 FullNode 和 SolidityNode
     */
    private List<TronNodeEndpointProperties> nodes = new ArrayList<>();
}
