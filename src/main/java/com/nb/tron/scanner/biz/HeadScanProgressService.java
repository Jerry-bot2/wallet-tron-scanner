package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.service.ITronScanCheckpointService;
import com.nb.tron.scanner.support.HeadScanStatistics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 固化扫描进度持久化服务。
 *
 * <p>由扫块任务串行调用，只保存最后一个已经完整处理的固化区块。</p>
 * <p>
 * Author: bin jack
 * Date: 06.10.26
 */
@Service
@RequiredArgsConstructor
public class HeadScanProgressService {

    private final TronScannerProperties scannerProperties;
    private final TronNodeManager nodeManager;
    private final ITronScanCheckpointService checkpointService;

    /**
     * 已有进度直接返回；首次启动保存起点前一块。
     * 例如从 1000 开始，保存 999 的高度和 Hash，返回后本轮从 1000 扫描。
     * 从 0 开始时保存 -1/空 Hash，不请求负数区块。
     */
    public TronScanCheckpoint loadCheckpoint() {
        return HeadScanStatistics.recordCheckpointLoad(this::loadOrInitializeCheckpoint);
    }

    private TronScanCheckpoint loadOrInitializeCheckpoint() {
        // 1. 已有进度直接使用；例如已扫 1000，就交回 1000/H1000。
        TronScanCheckpoint checkpoint = checkpointService.findByNetwork(scannerProperties.getChainNetwork());
        if (checkpoint != null) {
            return checkpoint;
        }

        // 2. 首次从1000开始，先读取999的Hash，再保存初始检查点。
        long initialHeight = scannerProperties.getStartBlockHeight() - 1;
        String initialHash = initialHeight < 0 ? "" : nodeManager.getBlockHeaderByHeight(initialHeight).blockId();
        TronScanCheckpoint initial = new TronScanCheckpoint()
            .setChainNetwork(scannerProperties.getChainNetwork())
            .setLastBlockNumber(initialHeight)
            .setLastBlockHash(initialHash);

        // 3. 保存唯一检查点，成功后本轮从1000开始扫描。
        BizAssert.isTrue(checkpointService.save(initial), ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_SAVE_FAILED);
        return initial;
    }

    /**
     * 当前区块已解析且Kafka已ACK后推进检查点，成功后返回新进度。
     * 例如1000已完成、正在处理1001：更新失败时数据库仍停在1000，下轮重试1001。
     */
    public TronScanCheckpoint advance(TronScanCheckpoint checkpoint, TronBlockData blockData) {
        return HeadScanStatistics.timeProgressCommit(() -> persistBlock(checkpoint, blockData));
    }

    private TronScanCheckpoint persistBlock(TronScanCheckpoint checkpoint, TronBlockData blockData) {
        TronScanCheckpoint next = new TronScanCheckpoint()
            .setChainNetwork(checkpoint.getChainNetwork())
            .setLastBlockNumber(blockData.blockHeight())
            .setLastBlockHash(blockData.blockId());
        boolean saved = checkpointService.updatePosition(
            next.getChainNetwork(), next.getLastBlockNumber(), next.getLastBlockHash());
        BizAssert.isTrue(saved, ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_SAVE_FAILED);
        return next;
    }
}
