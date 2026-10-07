package com.nb.tron.scanner.biz;

import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.HeadBlockCheckResult;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.mq.publisher.DepositDiscoveryPublisher;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.parser.TronBlockParser;
import com.nb.tron.scanner.support.HeadScanStatistics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 单线程扫块入口：加载进度 → 检查分叉 → 回退或顺序扫描。
 * <p>发现分叉就回退并结束本轮，下轮重扫；正常扫描必须等 Kafka ACK 后才提交进度。</p>
 * <p>
 * Author: bin jack
 * Date: 06.10.26
 */
@Service
@RequiredArgsConstructor
public class HeadBlockScanService {

    private final TronScannerProperties scannerProperties;
    private final TronAddressIndex addressIndex;
    private final TronCurrencyIndex currencyIndex;
    private final TronNodeManager nodeManager;
    private final TronBlockParser blockParser;
    private final DepositDiscoveryPublisher depositPublisher;
    private final HeadBlockContinuityService continuityService;
    private final HeadScanProgressService progressService;

    /**
     * 扫块入口：先检查分叉；有分叉走回退流程，没有分叉走正常扫描流程。
     * <p>入口只包裹一次统计；业务步骤见 {@link #scanRound()}。</p>
     *
     * @return 本轮完成的区块数；发现分叉或区块接不上时结束本轮，返回 0
     */
    public int scanBlocks() {
        return HeadScanStatistics.recordRound(scannerProperties.getChainNetwork(), this::scanRound);
    }

    /**
     * 执行一轮业务流程：加载进度 → 复查末块 → 分叉回退或顺序扫描。
     * 耗时与结果由 scanBlocks 统一汇总，统计不改变处理顺序。
     */
    private int scanRound() {
        // 1. 确认地址、币种索引已加载，再读取扫描进度，例如上次扫到 1000/H1000。
        requireIndexesReady();
        TronScanCheckpoint checkpoint = progressService.loadCheckpoint();

        // 2. 比较节点的 1000 Hash；不同就进入分叉复查，回退后结束本轮，下轮重扫。
        HeadBlockCheckResult checkResult = continuityService.checkCheckpoint(checkpoint);
        if (checkResult.fork()) {
            continuityService.handleFork(checkpoint);
            return 0;
        }

        // 3. 没有分叉：从 1001 开始逐块处理，收到 Kafka ACK 后才保存扫描进度。
        return scanNewBlocks(checkpoint);
    }

    /**
     * 确定本轮扫描范围，再按高度逐块处理。
     * 例如已扫 1000，节点到 1200、单次上限 100，本轮只处理 1001～1100。
     * 下一块接不上就复查分叉并结束本轮；能接上才解析、发送和提交进度。
     */
    private int scanNewBlocks(TronScanCheckpoint checkpoint) {
        // 先确定本轮终点：不超过节点高度，也不超过单次扫描上限。
        long headHeight = nodeManager.getHeadHeight().blockHeight();
        long scanEndHeight = Math.min(headHeight, checkpoint.getLastBlockNumber() + scannerProperties.getMaxBlocksPerRun());

        int scannedCount = 0;
        for (long nextBlockHeight = checkpoint.getLastBlockNumber() + 1; nextBlockHeight <= scanEndHeight; nextBlockHeight++) {
            // 1. 读取下一块：已扫 1000，就读取 1001。
            TronBlockData blockData = nodeManager.getBlockDataByHeight(nextBlockHeight);

            // 2. 检查父 Hash：1001 必须接在 H1000 后面；接不上就复查并结束本轮。
            if (!continuityService.isNextBlockContinuous(checkpoint, blockData)) {
                continuityService.handleParentHashMismatch(checkpoint, blockData);
                return 0;
            }

            // 3. 处理并推进：解析充值、等待 Kafka ACK、事务保存，成功后继续 1002。
            checkpoint = processBlock(checkpoint, blockData);
            scannedCount++;
        }
        return scannedCount;
    }

    /**
     * 发送失败不保存进度；提交失败允许重发，由 chain-server 幂等接收。
     */
    private TronScanCheckpoint processBlock(TronScanCheckpoint checkpoint, TronBlockData blockData) {
        // 1. 从区块中解析平台监控地址收到的充值。
        List<TronDepositEvent> deposits = blockParser.parse(blockData);

        // 2. 有充值才发送 Kafka；本区块全部消息收到 ACK 后才推进进度，中途失败直接结束。
        if (!deposits.isEmpty()) {
            depositPublisher.publishAndWait(blockData, deposits);
        }

        // 3. 没有充值或发送已成功，事务保存区块摘要和扫描进度。
        return progressService.advance(checkpoint, blockData);
    }

    private void requireIndexesReady() {
        BizAssert.isTrue(addressIndex.isReady(), ScannerBizErrCode.ADDRESS_INDEX_NOT_READY);
        BizAssert.isTrue(currencyIndex.isReady(), ScannerBizErrCode.CURRENCY_INDEX_NOT_READY);
    }
}
