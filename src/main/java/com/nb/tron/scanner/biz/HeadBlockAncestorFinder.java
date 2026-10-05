package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.algorithm.BinarySearch;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.node.TronBlockHeaderReader;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.service.ITronScannedBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 查找本地与链上 Hash 相同、高度最大的区块。
 *
 * <p>1. 末块相同，直接返回当前扫描位置。<br>
 * 2. 末块不同，在保留的区块摘要中二分查找。<br>
 * 3. 复核结果后返回，回退操作由 {@link HeadBlockContinuityService} 负责。</p>
 * <p>一次查找固定使用一个 FullNode，读取失败结束本轮，下轮重新查找。</p>
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
@Service
@RequiredArgsConstructor
public class HeadBlockAncestorFinder {

    private final TronNodeManager nodeManager;

    private final ITronScannedBlockService scannedBlockService;

    /**
     * 找到本地与链上仍然相同的最后一块。
     *
     * <p>1. 检查末块：扫描到 1010，节点的 1010 Hash 相同，直接返回 1010。<br>
     * 2. 查找历史：末块不同，二分查找；例如 1008 相同、1009 不同，得到 1008。<br>
     * 3. 复核结果：确认查找期间链没有变化，再返回 1008，交给调用方回退。</p>
     *
     * @param checkpoint 当前扫描进度，调用前已确认对应摘要存在，且高度不小于 0
     * @return 最近的共同区块；末块相同时就是当前扫描位置，不代表已固化
     */
    public TronScannedBlock findCommonAncestor(TronScanCheckpoint checkpoint) {
        // 1. 固定一个节点，末块相同就直接返回
        TronBlockHeaderReader reader = nodeManager.openBlockHeaderReader(checkpoint.getLastBlockNumber());
        TronNodeHeight lastBlockOnNode = reader.getBlockHeaderByHeight(checkpoint.getLastBlockNumber());
        if (Objects.equals(checkpoint.getLastBlockHash(), lastBlockOnNode.blockId())) {
            return toScannedBlock(checkpoint);
        }

        // 2. 确认查找起点，再二分找到最后相同的区块
        TronScannedBlock searchStart = loadSearchStart(reader, checkpoint);
        long commonHeight = BinarySearch.findLastMatch(
            searchStart.getBlockNumber(),
            checkpoint.getLastBlockNumber(),
            height -> isSameBlock(reader, loadBlock(checkpoint.getChainNetwork(), height)));
        TronScannedBlock commonAncestor = loadBlock(checkpoint.getChainNetwork(), commonHeight);

        // 3. 复核本次查找结果，通过后交给调用方
        verifyCommonAncestor(reader, commonAncestor, lastBlockOnNode);
        return commonAncestor;
    }

    /**
     * 用最早保留的摘要作为查找起点，其 Hash 必须仍与节点相同。
     * 例如只保留 1000～1010，连 1000 都不同，就无法在这段历史中找到共同区块，停止本轮。
     */
    private TronScannedBlock loadSearchStart(TronBlockHeaderReader reader, TronScanCheckpoint checkpoint) {
        TronScannedBlock firstBlock = scannedBlockService.findOldestBlock(checkpoint.getChainNetwork());
        BizAssert.notNull(firstBlock, ScannerBizErrCode.HEAD_SCAN_HISTORY_INVALID);
        BizAssert.isTrue(isSameBlock(reader, firstBlock), ScannerBizErrCode.HEAD_SCAN_COMMON_ANCESTOR_NOT_FOUND);
        return firstBlock;
    }

    /**
     * 返回前再核对三件事：末块没变、共同区块仍相同、共同区块的下一块仍不同。
     * 例如找到 1008：复查 1010 没变、1008 相同、1009 不同，才允许回退到 1008。
     * 任一项不满足或节点读取失败，结束本轮，不使用本次结果。
     */
    private void verifyCommonAncestor(TronBlockHeaderReader reader, TronScannedBlock commonAncestor, TronNodeHeight lastBlockOnNode) {
        // 1. 节点末块必须与查找开始时相同
        TronNodeHeight currentLastBlock = reader.getBlockHeaderByHeight(lastBlockOnNode.blockHeight());
        BizAssert.isTrue(Objects.equals(lastBlockOnNode.blockId(), currentLastBlock.blockId()),
            ScannerBizErrCode.HEAD_SCAN_CHAIN_CHANGED);

        // 2. 找到的共同区块必须仍然相同
        BizAssert.isTrue(isSameBlock(reader, commonAncestor), ScannerBizErrCode.HEAD_SCAN_CHAIN_CHANGED);

        // 3. 共同区块的下一块必须仍然不同，才能确认找到的是最后相同的区块
        TronScannedBlock nextBlock = loadBlock(commonAncestor.getChainNetwork(), commonAncestor.getBlockNumber() + 1);
        BizAssert.isTrue(!isSameBlock(reader, nextBlock), ScannerBizErrCode.HEAD_SCAN_CHAIN_CHANGED);
    }

    /**
     * 比较同一高度的本地 Hash 与节点 Hash。RPC 失败直接抛出，不能当作 Hash 不同。
     */
    private boolean isSameBlock(TronBlockHeaderReader reader, TronScannedBlock localBlock) {
        // -1 是创世块之前的初始标记，没有对应链上区块，不发送节点请求。
        if (localBlock.getBlockNumber() == -1) {
            return Objects.equals(localBlock.getBlockHash(), "");
        }
        TronNodeHeight nodeBlock = reader.getBlockHeaderByHeight(localBlock.getBlockNumber());
        return Objects.equals(localBlock.getBlockHash(), nodeBlock.blockId());
    }

    private TronScannedBlock loadBlock(String chainNetwork, long blockHeight) {
        TronScannedBlock block = scannedBlockService.findByHeight(chainNetwork, blockHeight);
        BizAssert.notNull(block, ScannerBizErrCode.HEAD_SCAN_HISTORY_INVALID);
        return block;
    }

    private TronScannedBlock toScannedBlock(TronScanCheckpoint checkpoint) {
        return new TronScannedBlock()
            .setChainNetwork(checkpoint.getChainNetwork())
            .setBlockNumber(checkpoint.getLastBlockNumber())
            .setBlockHash(checkpoint.getLastBlockHash());
    }
}
