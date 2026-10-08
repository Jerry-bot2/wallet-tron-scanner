package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.HeadBlockCheckResult;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronNodeHeight;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.support.HeadScanStatistics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 区块连续性：比较末块和父 Hash，发现分叉时查找共同区块并回退。
 *
 * <p>回退点在这里选定并核验；数据库事务交给进度服务。</p>
 * <p>
 * Author: bin jack
 * Date: 06.10.26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeadBlockContinuityService {

    private final TronNodeManager nodeManager;
    private final HeadBlockAncestorFinder ancestorFinder;
    private final HeadScanProgressService progressService;

    /**
     * 比较上次扫描末块与节点同高度区块的 Hash。
     * 例如本地是 1000/H1000，节点是 1000/H1000'，返回 fork=true，由扫块入口处理分叉。
     *
     * @return 分叉检查结果；节点读取失败时抛出异常，不返回检查结果
     */
    public HeadBlockCheckResult checkCheckpoint(TronScanCheckpoint checkpoint) {
        // 1. -1 表示尚未扫描创世块，没有已扫区块可比较。
        if (checkpoint.getLastBlockNumber() < 0) {
            return new HeadBlockCheckResult(false);
        }
        // 2. 读取上次扫描的同一高度，例如上次扫到 1000，就读取节点的 1000。
        TronNodeHeight nodeBlock = nodeManager.getBlockHeaderByHeight(checkpoint.getLastBlockNumber());
        // 3. 同高度 Hash 不同，明确标记 fork=true。
        boolean fork = !Objects.equals(checkpoint.getLastBlockHash(), nodeBlock.blockId());
        return new HeadBlockCheckResult(fork);
    }

    /**
     * 每块只比较：高度 = 当前高度 + 1，父 Hash = 当前 Hash。
     * 例如刚扫完 1000，1001 的父 Hash 必须是 H1000；一轮内也可能分叉，因此逐块比较。
     */
    public boolean isNextBlockContinuous(TronScanCheckpoint checkpoint, TronBlockData blockData) {
        BizAssert.isTrue(blockData.blockHeight() == checkpoint.getLastBlockNumber() + 1, ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        return checkpoint.getLastBlockNumber() < 0
            || Objects.equals(checkpoint.getLastBlockHash(), blockData.parentBlockId());
    }

    /**
     * 下一块的父 Hash 接不上时，先复查分叉，再决定是否冷却节点。
     * 例如已扫 1000，读到的 1001 父 Hash 不是 H1000：
     * 1. 复查发现 1000 已变化，按同一个分叉流程回退。
     * 2. 复查发现 1000 仍正确，保持进度，冷却返回不一致数据的节点，下轮重读。
     * 两种情况处理完后，都由扫块入口结束本轮。
     */
    public void handleParentHashMismatch(TronScanCheckpoint checkpoint, TronBlockData blockData) {
        // 1. 已确认分叉并回退，就交回扫描入口结束本轮。
        if (handleFork(checkpoint)) {
            return;
        }
        // 2. 末块仍正确、下一块却接不上：暂时排除本次数据节点，下轮换节点重读。
        nodeManager.startRecoveryCooldown(blockData.nodeCode());
    }

    /**
     * 1. 找保留摘要中最后相同的区块；找不到就报错，保持原进度。
     * 2. 事务回退；返回后由扫块入口结束本轮，下轮从共同区块加一重扫。
     * <p>例如 1000 → 998，下轮从 999 扫描。
     * 复查时末块又相同（例如换节点后仍是 1000/H1000），保持原进度。</p>
     *
     * @return true 已回退；false 复查末块仍相同，没有回退
     */
    public boolean handleFork(TronScanCheckpoint checkpoint) {
        HeadScanStatistics.markForkRecheck();
        // 1. 例如已扫 1000，查到最后相同的区块是 998。
        TronScannedBlock commonBlock = ancestorFinder.findCommonAncestor(checkpoint);
        BizAssert.notNull(commonBlock, ScannerBizErrCode.HEAD_SCAN_COMMON_ANCESTOR_NOT_FOUND);
        // 2. 复查时 1000 又相同，例如读取期间换了节点：保持原进度，结束本轮。
        if (Objects.equals(commonBlock.getBlockNumber(), checkpoint.getLastBlockNumber())) {
            return false;
        }
        // 3. 回退到 998，删除 999、1000 的旧摘要，下轮从 999 重扫。
        long previousHeight = checkpoint.getLastBlockNumber();
        progressService.rewind(checkpoint, commonBlock);
        log.warn("TRON Head分叉回退完成，chainNetwork={}，previousHeight={}，rewindHeight={}，下轮重扫",
            checkpoint.getChainNetwork(), previousHeight, commonBlock.getBlockNumber());
        return true;
    }
}
