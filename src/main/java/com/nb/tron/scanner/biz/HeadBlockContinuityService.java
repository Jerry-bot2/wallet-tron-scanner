package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizAssert;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.node.TronNodeManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Head 区块连续性检查
 *
 * <p>1. 每轮检查上次扫描的区块是否改变。<br>
 * 2. 每块检查父 Hash 是否接得上本地进度。<br>
 * 3. 发现分叉时，找到最近的共同区块，回退进度，下轮重扫。</p>
 * <p>这里只处理扫描连续性，充值固化确认由 chain-server 完成。</p>
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
@Service
@RequiredArgsConstructor
public class HeadBlockContinuityService {

    private final HeadBlockAncestorFinder ancestorFinder;
    private final HeadScanProgressService progressService;
    private final TronNodeManager nodeManager;

    /**
     * 检查上次扫描的区块有没有被替换，即使没有新区块也需要检查。
     * 例如保存的是 1010/H1010，节点返回 1010/New1010，就查找共同区块并回退。
     * 事务: 检查点回退 和 删除摘要
     * <p>
     * 每轮检查末块：发现两轮任务之间发生的分叉。
     * 和checkNextBlock不同的是: checkNextBlock 是本轮扫描期间发生的分叉
     * </p>
     */
    public void checkCheckpoint(TronScanCheckpoint checkpoint) {
        // -1 表示准备从创世块 0 开始扫描，还没有上一块可以检查
        if (checkpoint.getLastBlockNumber() < 0) {
            return;
        }
        // 找到本地和节点仍然相同的最后一块
        TronScannedBlock commonAncestor = findCommonAncestorOrRestart(checkpoint);

        // 判断是否需要回退: 比较“最后相同的位置”和“当前扫描位置”
        // commonAncestor返回1010 当前进度 1010 此时进度正确没有分叉
        // commonAncestor返回1008 当前进度 1010 说明分叉 1009 1010属于旧分支 需要重新扫描
        if (commonAncestor.getBlockNumber() < checkpoint.getLastBlockNumber()) {
            rewind(checkpoint, commonAncestor);
        }
    }

    /**
     * 下一块的高度必须加一，父 Hash 必须等于上次扫描的 Hash。
     * 例如进度为 1010/H1010，下一块必须是 1011，父 Hash 必须为 H1010。
     * <p>
     * 每块检查父 Hash：发现本轮扫描期间发生的分叉
     * 扫描过程发生分叉 -> 找到最近共同的区块 -> 检查点回退同时删除旧摘要 -> 结束本轮 -> 下一轮新的检查点开始扫描
     * </p>
     * 下一块接不上，上次扫描位置仍然正确: 说明本次选择的节点是异常数据节点 -> 当前节点放入冷却期 -> 检查点仍然保持旧的 -> 本轮结束
     * </p>
     */
    public void checkNextBlock(TronScanCheckpoint checkpoint, TronBlockData blockData) {
        BizAssert.isTrue(blockData.blockHeight() == checkpoint.getLastBlockNumber() + 1, ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        if (checkpoint.getLastBlockNumber() < 0 || Objects.equals(checkpoint.getLastBlockHash(), blockData.parentBlockId())) {
            return;
        }

        TronScannedBlock commonAncestor = findCommonAncestorOrRestart(checkpoint);
        // 末块仍正确，说明新块数据接不上；冷却返回该块的节点，下轮换节点重读。
        if (Objects.equals(commonAncestor.getBlockNumber(), checkpoint.getLastBlockNumber())) {
            nodeManager.startRecoveryCooldown(blockData.nodeCode());
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        }
        rewind(checkpoint, commonAncestor);
    }

    /**
     * 1. 先在保留摘要中查找共同区块，普通分叉使用最近共同区块恢复。<br>
     * 2. 连初始边界都不同时，自动重放已记录的完整范围，避免下一轮一直查找失败。<br>
     * 3. 节点读取失败、摘要缺失等其他异常直接抛出，不触发全量重放。
     */
    private TronScannedBlock findCommonAncestorOrRestart(TronScanCheckpoint checkpoint) {
        try {
            return ancestorFinder.findCommonAncestor(checkpoint);
        } catch (BizException exception) {
            if (exception.getErrorCode() != ScannerBizErrCode.HEAD_SCAN_COMMON_ANCESTOR_NOT_FOUND) {
                throw exception;
            }
            TronScanCheckpoint restarted = progressService.restartFromInitialBoundary(checkpoint);
            throw BizException.of(ScannerBizErrCode.HEAD_SCAN_FORK_DETECTED,
                checkpoint.getLastBlockNumber(), restarted.getLastBlockNumber());
        }
    }

    /**
     * 共同区块为 1008 时，进度改为 1008，删除 1009 之后的旧摘要。
     * 回退后结束本轮，下一轮从 1009 重扫，避免继续使用内存中的旧进度。
     */
    private void rewind(TronScanCheckpoint checkpoint, TronScannedBlock commonAncestor) {
        progressService.rewind(checkpoint, commonAncestor);
        throw BizException.of(ScannerBizErrCode.HEAD_SCAN_FORK_DETECTED,
            checkpoint.getLastBlockNumber(), commonAncestor.getBlockNumber());
    }
}
