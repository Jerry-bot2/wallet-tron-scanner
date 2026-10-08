package com.nb.tron.scanner.node;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.support.HeadScanStatistics;
import com.nb.tron.scanner.support.TronSdkCalls;
import com.nb.tron.sdk.client.TronNodeClient;
import com.nb.tron.sdk.model.TronNodeHeight;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * 固定节点的区块头读取器，由 {@link TronNodeManager} 创建。
 *
 * <p>一次共同区块查找始终使用同一个 FullNode，避免把主备节点的不同分支混在一起。
 * 读取失败时将节点放入冷却期并抛出异常；下一轮可以选择备用节点重新查找。</p>
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public class TronBlockHeaderReader {

    private final TronNodeClient nodeClient;
    private final TronNodeEndpointProperties endpoint;
    private final Runnable onReadFailure;

    public TronNodeHeight getBlockHeaderByHeight(long blockHeight) {
        try {
            return HeadScanStatistics.timeNodeRead(() -> TronSdkCalls.execute(
                () -> nodeClient.getBlockHeaderByHeight(endpoint.toSdkEndpoint(), blockHeight)));
        } catch (BizException exception) {
            onReadFailure.run();
            throw exception;
        }
    }
}
