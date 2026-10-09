package com.nb.tron.scanner.node;

import com.nb.tron.scanner.support.HeadScanStatistics;
import com.nb.tron.scanner.support.TronSdkCalls;
import com.nb.tron.sdk.block.TronBlockGateway;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronNodeHeight;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Scanner的TRON节点读取入口。
 *
 * <p>节点健康状态、优先级、冷却和主备切换由SDK节点池统一管理；本类只说明每种扫描操作
 * 需要哪类节点、是否要求指定高度，以及如何记录扫描性能。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
@RequiredArgsConstructor
public class TronNodeManager {

    private final TronBlockGateway blockGateway;

    /**
     * 查询最新Head高度，并立即回写实际响应节点的高度快照。
     */
    public TronNodeHeight getHeadHeight() {
        TronNodeHeight height = read(blockGateway::getHeadHeight);
        HeadScanStatistics.observeHead(height.blockHeight());
        return height;
    }

    public TronNodeHeight getSolidHeight() {
        return read(blockGateway::getSolidHeight);
    }

    /**
     * 读取区块和回执的整个过程由同一个FullNode完成；失败后整次切换备用节点重读。
     */
    public TronBlockData getBlockDataByHeight(long blockHeight) {
        return read(() -> blockGateway.getBlockDataByHeight(blockHeight));
    }

    public TronNodeHeight getBlockHeaderByHeight(long blockHeight) {
        return read(() -> blockGateway.getBlockHeaderByHeight(blockHeight));
    }

    /**
     * 共同区块查找期间固定使用一个已同步到扫描进度的FullNode。
     */
    public TronBlockHeaderReader openBlockHeaderReader(long requiredBlockHeight) {
        return new TronBlockHeaderReader(TronSdkCalls.execute(() ->
            blockGateway.openBlockHeaderReader(requiredBlockHeight)));
    }

    /**
     * 节点返回了不连续区块时主动冷却，下一轮重新选择其他节点。
     */
    public void startRecoveryCooldown(String nodeCode) {
        TronSdkCalls.execute(() -> {
            blockGateway.startRecoveryCooldown(nodeCode);
            return null;
        });
    }

    private <T> T read(Supplier<T> operation) {
        return HeadScanStatistics.timeNodeRead(() -> TronSdkCalls.execute(operation));
    }
}
