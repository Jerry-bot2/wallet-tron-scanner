package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronNodeHeight;
import com.nb.tron.scanner.node.TronNodeManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 固化区块连续性校验。
 *
 * <p>检查点或新区块不连续时停止扫描并等待人工确认，不自动修改扫描进度。</p>
 * <p>
 * Author: bin jack
 * Date: 06.10.26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeadBlockContinuityService {

    private final TronNodeManager nodeManager;

    /**
     * 固化检查点必须与节点同高度区块保持一致，否则停止扫描并等待人工确认。
     */
    public void verifyCheckpoint(TronScanCheckpoint checkpoint) {
        if (checkpoint.getLastBlockNumber() < 0) {
            return;
        }

        TronNodeHeight chainBlock = nodeManager.getBlockHeaderByHeight(checkpoint.getLastBlockNumber());
        if (Objects.equals(checkpoint.getLastBlockHash(), chainBlock.blockId())) {
            return;
        }

        log.error("TRON固化检查点Hash不一致，停止扫描并等待人工确认，errorCode={}，chainNetwork={}，"
                + "nodeCode={}，blockNumber={}，checkpointHash={}，chainHash={}",
            ScannerBizErrCode.SOLIDIFIED_SCAN_CHAIN_INVALID.getCode(), checkpoint.getChainNetwork(),
            chainBlock.nodeCode(), checkpoint.getLastBlockNumber(), checkpoint.getLastBlockHash(), chainBlock.blockId());
        throw BizException.of(ScannerBizErrCode.SOLIDIFIED_SCAN_CHAIN_INVALID,
            checkpoint.getLastBlockNumber());
    }

    /**
     * 固化新区块的高度和父Hash必须接上当前检查点，否则停止扫描并等待人工确认。
     */
    public void verifyNextBlock(TronScanCheckpoint checkpoint, TronBlockData blockData) {
        long expectedBlockNumber = checkpoint.getLastBlockNumber() + 1;
        boolean correctParent = checkpoint.getLastBlockNumber() < 0
            || Objects.equals(checkpoint.getLastBlockHash(), blockData.parentBlockId());
        if (blockData.blockHeight() == expectedBlockNumber && correctParent) {
            return;
        }

        log.error("TRON固化区块不连续，停止扫描并等待人工确认，errorCode={}，chainNetwork={}，nodeCode={}，"
                + "expectedBlockNumber={}，actualBlockNumber={}，expectedParentHash={}，actualParentHash={}",
            ScannerBizErrCode.SOLIDIFIED_SCAN_CHAIN_INVALID.getCode(), checkpoint.getChainNetwork(),
            blockData.nodeCode(), expectedBlockNumber, blockData.blockHeight(),
            checkpoint.getLastBlockHash(), blockData.parentBlockId());
        throw BizException.of(ScannerBizErrCode.SOLIDIFIED_SCAN_CHAIN_INVALID,
            blockData.blockHeight());
    }
}
