package com.nb.tron.scanner.node;

import com.nb.tron.scanner.support.HeadScanStatistics;
import com.nb.tron.scanner.support.TronSdkCalls;
import com.nb.tron.sdk.model.TronNodeHeight;
import lombok.RequiredArgsConstructor;

/**
 * 一轮共同区块查找使用的固定节点读取器。
 *
 * <p>SDK会话保证查找过程中不切换节点；读取失败后结束本轮，下一轮重新选择节点。</p>
 * <p>
 * Author: bin jack
 * Date: 06.10.26
 */
@RequiredArgsConstructor
public class TronBlockHeaderReader {

    private final com.nb.tron.sdk.block.TronBlockHeaderReader delegate;

    public TronNodeHeight getBlockHeaderByHeight(long blockHeight) {
        return HeadScanStatistics.timeNodeRead(() -> TronSdkCalls.execute(() ->
            delegate.getBlockHeaderByHeight(blockHeight)));
    }
}
