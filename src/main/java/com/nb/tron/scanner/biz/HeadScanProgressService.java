package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizAssert;
import com.nb.mybatis.transaction.TransactionSupport;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.service.ITronScanCheckpointService;
import com.nb.tron.scanner.service.ITronScannedBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Head 扫描进度持久化。
 *
 * <p>1. 初始化检查点和摘要；2. 原子保存摘要与进度并清理旧记录；3. 原子回退。
 * 节点读取、充值解析和 Kafka 发送均在数据库事务之外完成。</p>
 * <p>
 * Author: bin jack
 * Date: 05.10.26
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
     * 读取或初始化扫描进度
     *
     * <p>
     * 1. 已有进度：核对数据库中的区块摘要，返回进度，继续扫描下一块。<br>
     * 2. 没有进度：读取起点前一块的 FullNode Hash，一起保存初始检查点和摘要。<br>
     * 3. 例如起点为 100，首次保存 99；以后扫描到 10000，重启后继续扫描 10001。
     * </p>
     */
    public TronScanCheckpoint loadCheckpoint() {
        String chainNetwork = scannerProperties.getChainNetwork();
        TronScanCheckpoint checkpoint = checkpointService.findByNetwork(chainNetwork);
        if (checkpoint == null) {
            return initializeCheckpoint(chainNetwork);
        }

        requireSummary(checkpoint);
        return checkpoint;
    }

    /**
     * 1. 先在事务外读取初始区块，避免请求节点期间占用数据库锁。<br>
     * 2. 在同一个事务中插入检查点和初始摘要，任何一步失败全部回滚。<br>
     * 3. 保存成功后直接返回初始检查点；重复初始化由唯一键拦截，下一轮读取已有进度。
     */
    private TronScanCheckpoint initializeCheckpoint(String chainNetwork) {
        TronScanCheckpoint initial = createInitialCheckpoint(chainNetwork);
        return transactionSupport.executeWithResult(() -> {
            BizAssert.isTrue(checkpointService.save(initial), ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_CONFLICT);
            scannedBlockService.saveBlock(toSummary(initial));
            return initial;
        });
    }

    /**
     * 起点为 100 时，保存 FullNode 的 99 号区块作为初始检查点和查找下界；它不代表已固化。
     * 起点为 0 时，用 -1 和空 Hash 表示还未扫描任何区块。
     */
    private TronScanCheckpoint createInitialCheckpoint(String chainNetwork) {
        long blockHeight = scannerProperties.getStartBlockHeight() - 1;
        String blockHash = blockHeight < 0 ? ""
            : nodeManager.getBlockHeaderByHeight(blockHeight).blockId();
        return new TronScanCheckpoint()
            .setChainNetwork(chainNetwork)
            .setLastBlockNumber(blockHeight)
            .setLastBlockHash(blockHash);
    }

    /**
     * 提交当前区块的扫描进度
     *
     * <p>1. 锁定检查点，确认数据库进度没有改变。<br>
     * 2. 保存本次处理完成的区块摘要。<br>
     * 3. 将检查点推进到本次区块的高度和 Hash。<br>
     * 4. 清理保留范围之外的旧摘要。</p>
     * <p>例如当前为 1010，本次处理完成 1011：一起保存 1011 的摘要和检查点。
     * 四步在同一个手动事务中执行，任一步失败全部回滚；提交成功才返回新进度。</p>
     *
     * @param currentCheckpoint 处理本次区块之前的扫描进度
     * @param blockData 已处理完成的区块；有充值时须已收到 Kafka ACK
     * @return 提交成功后的新检查点，供下一块扫描使用
     */
    public TronScanCheckpoint advance(TronScanCheckpoint currentCheckpoint, TronBlockData blockData) {
        TronScanCheckpoint nextCheckpoint = createNextCheckpoint(currentCheckpoint, blockData);

        return transactionSupport.executeWithResult(() -> {
            // 1. 确认当前进度
            lockCheckpoint(currentCheckpoint);

            // 2. 保存本次区块摘要
            scannedBlockService.saveBlock(toSummary(nextCheckpoint));

            // 3. 推进扫描检查点
            advanceCheckpoint(currentCheckpoint, nextCheckpoint);

            // 4. 清理旧摘要
            pruneHistory(nextCheckpoint);
            return nextCheckpoint;
        });
    }

    /**
     * 用本次区块构造下一检查点，高度必须是当前进度加一。
     */
    private TronScanCheckpoint createNextCheckpoint(TronScanCheckpoint currentCheckpoint, TronBlockData blockData) {
        BizAssert.isTrue(blockData.blockHeight() == currentCheckpoint.getLastBlockNumber() + 1, ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        return new TronScanCheckpoint()
            .setChainNetwork(currentCheckpoint.getChainNetwork())
            .setLastBlockNumber(blockData.blockHeight())
            .setLastBlockHash(blockData.blockId());
    }

    /**
     * 按原高度和原 Hash 更新检查点；更新失败则抛出异常，让整个事务回滚。
     */
    private void advanceCheckpoint(TronScanCheckpoint currentCheckpoint, TronScanCheckpoint nextCheckpoint) {
        boolean advanced = checkpointService.advance(currentCheckpoint.getChainNetwork(),
            currentCheckpoint.getLastBlockNumber(), currentCheckpoint.getLastBlockHash(),
            nextCheckpoint.getLastBlockNumber(), nextCheckpoint.getLastBlockHash());
        BizAssert.isTrue(advanced, ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_CONFLICT);
    }

    /**
     * 正常扫块后删除太旧的摘要，只保留最近 blockHistorySize 条，默认 1000 条。
     * 即使没有分叉也要执行，避免摘要表不断增长。
     * <pre>
     * 扫描到 10000，保留最近 1000 条：
     * 保留：9001～10000
     * 删除：9001 之前的摘要
     * </pre>
     * <p>这里删除太旧的记录；分叉回退时，{@link #rewind} 删除共同区块之后的旧分支记录。
     * 清理与本次区块提交在同一个手动事务中完成，任一步失败全部回滚。</p>
     */
    private void pruneHistory(TronScanCheckpoint checkpoint) {
        long oldestHeight = Math.max(checkpoint.getLastBlockNumber() - scannerProperties.getBlockHistorySize() + 1, -1);
        scannedBlockService.removeBefore(checkpoint.getChainNetwork(), oldestHeight);
    }

    /**
     * 将扫描进度退回已核验的共同区块，并清理它之后的摘要。
     *
     * <p>
     * 1. 锁定当前进度，确认进度没有被其他任务修改、共同区块摘要仍然有效。<br>
     * 2. 将检查点的高度和 Hash 改为共同区块。<br>
     * 3. 删除共同区块之后的摘要，保留共同区块本身。</p>
     * <p>例如扫描到 1010、共同区块为 1008：检查点改为 1008，删除 1009～1010 的摘要。
     * 更新与删除在同一个事务中完成，任何一步失败全部回滚。</p>
     */
    public void rewind(TronScanCheckpoint checkpoint, TronScannedBlock commonAncestor) {
        transactionSupport.execute(() -> {
            // 1. 核对当前进度和共同区块摘要
            lockCheckpoint(checkpoint);
            requireStoredBlock(checkpoint, commonAncestor);

            // 2. 将扫描进度退回共同区块
            rewindCheckpoint(checkpoint, commonAncestor);

            // 3. 删除共同区块之后的摘要，没有记录时正常结束
            scannedBlockService.removeAfter(checkpoint.getChainNetwork(), commonAncestor.getBlockNumber());
        });
    }

    /**
     * 已处于共同区块高度时跳过更新；否则更新高度和 Hash，更新失败时终止事务。
     */
    private void rewindCheckpoint(TronScanCheckpoint checkpoint, TronScannedBlock commonAncestor) {
        if (Objects.equals(checkpoint.getLastBlockNumber(), commonAncestor.getBlockNumber())) {
            return;
        }

        boolean rewound = checkpointService.rewind(checkpoint.getChainNetwork(),
            checkpoint.getLastBlockNumber(), checkpoint.getLastBlockHash(),
            commonAncestor.getBlockNumber(), commonAncestor.getBlockHash());
        BizAssert.isTrue(rewound, ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_CONFLICT);
    }

    private void lockCheckpoint(TronScanCheckpoint expected) {
        requireSameCheckpoint(expected, checkpointService.lockByNetwork(expected.getChainNetwork()));
    }

    private void requireSameCheckpoint(TronScanCheckpoint expected, TronScanCheckpoint current) {
        BizAssert.isTrue(current != null
                && Objects.equals(expected.getLastBlockNumber(), current.getLastBlockNumber())
                && Objects.equals(expected.getLastBlockHash(), current.getLastBlockHash()),
            ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_CONFLICT);
    }

    /**
     * <p> 检查的是数据库中的两份记录是否一致 </p>
     */
    private void requireSummary(TronScanCheckpoint checkpoint) {
        TronScannedBlock summary = scannedBlockService.findByHeight(checkpoint.getChainNetwork(), checkpoint.getLastBlockNumber());
        BizAssert.isTrue(summary != null && Objects.equals(summary.getBlockHash(), checkpoint.getLastBlockHash()),
            ScannerBizErrCode.HEAD_SCAN_HISTORY_INVALID);
    }

    private void requireStoredBlock(TronScanCheckpoint checkpoint, TronScannedBlock block) {
        TronScannedBlock stored = scannedBlockService.findByHeight(checkpoint.getChainNetwork(), block.getBlockNumber());
        BizAssert.isTrue(Objects.equals(block.getChainNetwork(), checkpoint.getChainNetwork())
                && block.getBlockNumber() <= checkpoint.getLastBlockNumber()
                && stored != null && Objects.equals(stored.getBlockHash(), block.getBlockHash()),
            ScannerBizErrCode.HEAD_SCAN_HISTORY_INVALID);
    }

    private TronScannedBlock toSummary(TronScanCheckpoint checkpoint) {
        return new TronScannedBlock()
            .setChainNetwork(checkpoint.getChainNetwork())
            .setBlockNumber(checkpoint.getLastBlockNumber())
            .setBlockHash(checkpoint.getLastBlockHash());
    }
}
