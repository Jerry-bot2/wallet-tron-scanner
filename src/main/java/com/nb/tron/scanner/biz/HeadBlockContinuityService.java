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
     */
    public void checkCheckpoint(TronScanCheckpoint checkpoint) {
        // -1 表示准备从创世块 0 开始扫描，还没有上一块可以检查
        if (checkpoint.getLastBlockNumber() < 0) {
            return;
        }
        // 找到本地和节点仍然相同的最后一块
        TronScannedBlock commonAncestor = ancestorFinder.findCommonAncestor(checkpoint);

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
     */
    public void checkNextBlock(TronScanCheckpoint checkpoint, TronBlockData blockData) {
        BizAssert.isTrue(blockData.blockHeight() == checkpoint.getLastBlockNumber() + 1, ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        if (checkpoint.getLastBlockNumber() < 0 || Objects.equals(checkpoint.getLastBlockHash(), blockData.parentBlockId())) {
            return;
        }

        TronScannedBlock commonAncestor = ancestorFinder.findCommonAncestor(checkpoint);
        // 末块仍正确，说明新块数据接不上；冷却返回该块的节点，下轮换节点重读。
        if (Objects.equals(commonAncestor.getBlockNumber(), checkpoint.getLastBlockNumber())) {
            nodeManager.startRecoveryCooldown(blockData.nodeCode());
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        }
        rewind(checkpoint, commonAncestor);
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
