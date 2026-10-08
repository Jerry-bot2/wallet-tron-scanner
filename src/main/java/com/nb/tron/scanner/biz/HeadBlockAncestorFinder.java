package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.algorithm.BinarySearch;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.sdk.model.TronNodeHeight;
import com.nb.tron.scanner.node.TronBlockHeaderReader;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.service.ITronScannedBlockService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 查找保留摘要中与链上 Hash 相同、高度最大的区块。
 *
 * <p>1. 末块相同，直接返回当前扫描位置。<br>
 * 2. 末块不同，在保留的区块摘要中二分查找。<br>
 * 3. 复核结果后返回，回退操作由 {@link HeadScanProgressService#rewind} 负责。</p>
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
     * 找到保留摘要中与链上仍然相同的最后一块。
     *
     * <p>
     * 1. 检查末块：扫描到 1010，节点的 1010 Hash 相同，直接返回 1010。<br>
     * 2. 查找历史：末块不同，二分查找；例如 1008 相同、1009 不同，得到 1008。<br>
     * 3. 复核结果：确认查找期间链没有变化，再返回 1008，交给调用方回退。</p>
     * <p>
     * 只查连续保留的摘要；最早一块也不同时返回 null，由调用方报错，保持扫描进度。
     * </p>
     *
     * @param checkpoint 本轮读取的扫描进度，高度不小于 0
     * @return 近期共同区块；末块相同则返回末块，近期全部不同时返回 null
     */
    public TronScannedBlock findCommonAncestor(TronScanCheckpoint checkpoint) {
        // 1. 固定一个节点复查末块；例如 1000/H1000 又相同，就交回 1000，不需要回退。
        TronBlockHeaderReader reader = nodeManager.openBlockHeaderReader(checkpoint.getLastBlockNumber());
        TronNodeHeight lastBlockOnNode = reader.getBlockHeaderByHeight(checkpoint.getLastBlockNumber());
        if (Objects.equals(checkpoint.getLastBlockHash(), lastBlockOnNode.blockId())) {
            return toScannedBlock(checkpoint);
        }

        // 2. 读取最早保留的摘要；它也不同，说明共同区块已超出保留范围。
        TronScannedBlock oldestBlock = scannedBlockService.findOldestBlock(checkpoint.getChainNetwork());
        BizAssert.notNull(oldestBlock, ScannerBizErrCode.HEAD_SCAN_HISTORY_INVALID);
        if (!isSameBlock(reader, oldestBlock)) {
            return null;
        }

        // 3. 最早块相同、末块不同：二分找分界，例如 998 相同、999 不同，就得到 998。
        long commonHeight = BinarySearch.findLastMatch(
            oldestBlock.getBlockNumber(),
            checkpoint.getLastBlockNumber(),
            height -> isSameBlock(reader, loadScannedBlock(checkpoint.getChainNetwork(), height)));
        TronScannedBlock commonAncestor = loadScannedBlock(checkpoint.getChainNetwork(), commonHeight);

        // 4. 查询需要多次请求；返回前复核分支没有变化，才把共同区块交给回退流程。
        verifyCommonAncestor(reader, commonAncestor, lastBlockOnNode);
        return commonAncestor;
    }

    /**
     * 返回前再核对三件事：末块没变、共同区块仍相同、下一块仍不同。
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

        // 3. 近期摘要连续保存，直接核对共同区块的下一块
        TronScannedBlock nextBlock = loadScannedBlock(commonAncestor.getChainNetwork(), commonAncestor.getBlockNumber() + 1);
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

    /**
     * 按准确高度读取摘要。缺少记录时结束本轮，不使用其他高度替代。
     */
    private TronScannedBlock loadScannedBlock(String chainNetwork, long blockHeight) {
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
