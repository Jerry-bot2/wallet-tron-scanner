package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizAssert;
import com.nb.mybatis.transaction.TransactionSupport;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.constant.TronConstants;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.service.ITronScanCheckpointService;
import com.nb.tron.scanner.service.ITronScannedBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 扫描进度落库：初始化、推进进度、分叉回退、批量清理摘要。
 *
 * <p>由一个扫块任务串行调用。回退目标由连续性服务核验，本类负责检查点和摘要的事务落库。</p>
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
    private final ITronScannedBlockService scannedBlockService;
    private final TransactionSupport transactionSupport;

    /**
     * 已有进度直接返回；首次启动保存起点前一块。
     * 例如从 1000 开始，保存 999 的高度和 Hash，返回后本轮从 1000 扫描。
     * 从 0 开始时保存 -1/空 Hash，不请求负数区块。
     */
    public TronScanCheckpoint loadCheckpoint() {
        // 1. 已有进度直接使用；例如已扫 1000，就交回 1000/H1000。
        TronScanCheckpoint checkpoint = checkpointService.findByNetwork(scannerProperties.getChainNetwork());
        if (checkpoint != null) {
            return checkpoint;
        }

        // 2. 首次从 1000 开始，就读取 999 的 Hash；节点请求放在数据库事务之外。
        long initialHeight = scannerProperties.getStartBlockHeight() - 1;
        String initialHash = initialHeight < 0 ? "" : nodeManager.getBlockHeaderByHeight(initialHeight).blockId();
        TronScanCheckpoint initial = new TronScanCheckpoint()
            .setChainNetwork(scannerProperties.getChainNetwork())
            .setLastBlockNumber(initialHeight)
            .setLastBlockHash(initialHash);

        // 3. 一个事务保存起始进度和摘要，成功后本轮从 1000 开始扫。
        transactionSupport.execute(() -> {
            BizAssert.isTrue(checkpointService.save(initial), ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_SAVE_FAILED);
            scannedBlockService.saveBlock(toSummary(initial));
        });
        return initial;
    }

    /**
     * 当前区块已解析、Kafka 已 ACK：一起保存摘要和进度，成功后返回新进度。
     * 例如 1000 已完成、正在处理 1001：任一 SQL 失败，仍停在 1000，下轮重试 1001。
     */
    public TronScanCheckpoint advance(TronScanCheckpoint checkpoint, TronBlockData blockData) {
        TronScanCheckpoint next = new TronScanCheckpoint()
            .setChainNetwork(checkpoint.getChainNetwork())
            .setLastBlockNumber(blockData.blockHeight())
            .setLastBlockHash(blockData.blockId());
        transactionSupport.execute(() -> {
            // 1. 保存已完成区块的摘要
            scannedBlockService.saveBlock(toSummary(next));

            // 2. 将扫描位置推进到本次区块
            boolean saved = checkpointService.updatePosition(next.getChainNetwork(), next.getLastBlockNumber(), next.getLastBlockHash());
            BizAssert.isTrue(saved, ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_SAVE_FAILED);

            // 3. 每 100 个高度清理一次旧摘要
            pruneHistory(next);
        });
        return next;
    }

    /**
     * 回退到摘要表中已核验的共同区块，两步 SQL 在同一个手动事务中执行。
     * <p>
     * 1000 → 998：更新进度为 998，删除 999、1000 的旧摘要。
     * 998 及之前保留，下轮从 999 重扫；任何 SQL 失败全部回滚。
     * </p>
     * <p>回退完成后由调用方结束本轮，下轮重新加载数据库进度。</p>
     */
    public void rewind(TronScanCheckpoint checkpoint, TronScannedBlock commonBlock) {
        transactionSupport.execute(() -> {
            // 1. 将数据库进度从 1000 更新为共同区块 998/H998。
            boolean saved = checkpointService.updatePosition(checkpoint.getChainNetwork(), commonBlock.getBlockNumber(), commonBlock.getBlockHash());
            BizAssert.isTrue(saved, ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_SAVE_FAILED);

            // 2. 删除 998 之后的旧摘要；998 自身保留，下轮从 999 重扫。
            scannedBlockService.removeAfter(checkpoint.getChainNetwork(), commonBlock.getBlockNumber());
        });
    }

    /**
     * 默认连续保留最近 2 万条摘要，每 100 个高度清理一次。
     * 例如扫到 30000，保留 10001～30000；30001～30099 暂不清理。
     */
    private void pruneHistory(TronScanCheckpoint checkpoint) {
        if (checkpoint.getLastBlockNumber() % TronConstants.BLOCK_HISTORY_CLEANUP_INTERVAL != 0) {
            return;
        }
        long oldestHeight = Math.max(checkpoint.getLastBlockNumber() - scannerProperties.getBlockHistorySize() + 1, -1);
        scannedBlockService.removeBefore(checkpoint.getChainNetwork(), oldestHeight);
    }

    private TronScannedBlock toSummary(TronScanCheckpoint checkpoint) {
        return new TronScannedBlock()
            .setChainNetwork(checkpoint.getChainNetwork())
            .setBlockNumber(checkpoint.getLastBlockNumber())
            .setBlockHash(checkpoint.getLastBlockHash());
    }
}
