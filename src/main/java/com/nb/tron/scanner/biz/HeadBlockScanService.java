package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.mq.publisher.DepositDiscoveryPublisher;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.parser.TronBlockParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Head 区块扫描服务
 *
 * <p>每轮处理顺序：</p>
 * 1. 读取或初始化本地扫描检查点。<br>
 * 2. 核对上次扫描的区块 Hash，查询 FullNode 当前 Head 高度。<br>
 * 3. 读取下一高度的区块，连续性检查通过后解析充值。<br>
 * 4. 有充值时发送 Kafka，并等待 Broker ACK。<br>
 * 5. 当前区块完整处理成功后，在短事务中保存摘要并推进检查点。
 *
 * <p>任一步失败时，异常直接结束本轮任务，后续区块不再处理。
 * 下一轮重新读取数据库检查点，从最后成功高度的下一块继续。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Service
@RequiredArgsConstructor
public class HeadBlockScanService {

    private final TronScannerProperties scannerProperties;

    private final TronAddressIndex addressIndex;

    private final TronCurrencyIndex currencyIndex;

    private final TronNodeManager nodeManager;

    private final TronBlockParser blockParser;

    private final HeadBlockContinuityService blockContinuityService;

    private final DepositDiscoveryPublisher depositPublisher;

    private final HeadScanProgressService progressService;

    /**
     * 顺序追赶当前 Head 高度
     *
     * <p>例如 100 已处理成功、101 发送失败，数据库进度停留在 100。
     * 下次调度或服务重启后从 101 重试，不会跳到 102。</p>
     *
     * @return 本轮成功处理并推进检查点的区块数量
     */
    public int scanBlocks() {
        requireIndexesReady();
        TronScanCheckpoint checkpoint = progressService.loadCheckpoint();
        // 每轮开始先核对上次扫描的末块，即使没有新区块也要检查。
        // 例如保存 1010/H1010，节点已变为 1010/New1010，就查找共同区块并回退，下轮重扫。
        blockContinuityService.checkCheckpoint(checkpoint);
        long headBlockHeight = nodeManager.getHeadHeight().blockHeight();
        int scannedCount = 0;

        while (checkpoint.getLastBlockNumber() < headBlockHeight
            && scannedCount < scannerProperties.getMaxBlocksPerRun()) {
            checkpoint = scanNextBlock(checkpoint);
            scannedCount++;
        }
        return scannedCount;
    }

    private TronScanCheckpoint scanNextBlock(TronScanCheckpoint checkpoint) {
        long nextBlockHeight = checkpoint.getLastBlockNumber() + 1;
        TronBlockData blockData = nodeManager.getBlockDataByHeight(nextBlockHeight);
        // 一轮会连续扫多块，期间也可能分叉，所以每块都要检查能否接上当前进度。
        // 例如刚扫完 1011/H1011，1012 的父 Hash 却是 New1011，就需要重新核对分叉。
        // 正常情况只比较高度和父 Hash，不增加节点请求；接不上时才查找共同区块。
        blockContinuityService.checkNextBlock(checkpoint, blockData);
        List<TronDepositEvent> deposits = blockParser.parse(blockData);

        if (!deposits.isEmpty()) {
            depositPublisher.publishAndWait(blockData, deposits);
        }

        return progressService.advance(checkpoint, blockData);
    }

    private void requireIndexesReady() {
        BizAssert.isTrue(addressIndex.isReady(), ScannerBizErrCode.ADDRESS_INDEX_NOT_READY);
        BizAssert.isTrue(currencyIndex.isReady(), ScannerBizErrCode.CURRENCY_INDEX_NOT_READY);
    }
}
