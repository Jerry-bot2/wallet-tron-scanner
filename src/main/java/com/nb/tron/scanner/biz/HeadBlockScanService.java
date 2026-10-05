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
import com.nb.tron.scanner.service.ITronScanCheckpointService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Head 区块扫描服务
 *
 * <p>每轮处理顺序：</p>
 * 1. 读取或初始化本地扫描检查点。<br>
 * 2. 查询 FullNode 当前 Head 高度。<br>
 * 3. 从检查点下一高度开始顺序读取和解析区块。<br>
 * 4. 有充值时发送 Kafka，并等待 Broker ACK。<br>
 * 5. 当前区块完整处理成功后，条件更新数据库检查点。
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

    private final DepositDiscoveryPublisher depositPublisher;

    private final ITronScanCheckpointService checkpointService;

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
        TronScanCheckpoint checkpoint = loadCheckpoint();
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
        List<TronDepositEvent> deposits = blockParser.parse(blockData);

        if (!deposits.isEmpty()) {
            depositPublisher.publishAndWait(blockData, deposits);
        }

        // 当前区块处理成功后，将扫描进度推进到本次请求的高度。
        boolean advanced = checkpointService.advance(
            checkpoint.getChainNetwork(),
            checkpoint.getLastBlockNumber(),
            nextBlockHeight,
            blockData.blockId());
        BizAssert.isTrue(advanced, ScannerBizErrCode.HEAD_SCAN_CHECKPOINT_CONFLICT);

        return new TronScanCheckpoint()
            .setChainNetwork(checkpoint.getChainNetwork())
            .setLastBlockNumber(nextBlockHeight)
            .setLastBlockHash(blockData.blockId());
    }

    /**
     * 读取本次扫块使用的检查点。
     *
     * <p>检查点表示“最后一个已经完整处理成功的区块”：</p>
     * 1. 数据库已有检查点时直接返回。例如最后成功处理到 99，本轮从 100 开始。<br>
     * 2. 第一次扫描没有检查点时，根据 {@code startBlockHeight} 创建一条初始记录。<br>
     *
     * <p>例如配置 {@code startBlockHeight=100}，表示第一个需要扫描的是 100。
     * Scanner 会先查询 99 的区块 Hash，将 99 保存为最后已处理位置，随后主流程从 100 开始。</p>
     */
    private TronScanCheckpoint loadCheckpoint() {
        String chainNetwork = scannerProperties.getChainNetwork();
        TronScanCheckpoint checkpoint = checkpointService.findByNetwork(chainNetwork);
        if (checkpoint != null) {
            return checkpoint;
        }

        long previousBlockHeight = scannerProperties.getStartBlockHeight() - 1;
        String previousBlockHash = previousBlockHeight < 0
            ? ""
            : nodeManager.getBlockHeaderByHeight(previousBlockHeight).blockId();
        return checkpointService.initializeIfAbsent(new TronScanCheckpoint()
            .setChainNetwork(chainNetwork)
            .setLastBlockNumber(previousBlockHeight)
            .setLastBlockHash(previousBlockHash));
    }

    private void requireIndexesReady() {
        BizAssert.isTrue(addressIndex.isReady(), ScannerBizErrCode.ADDRESS_INDEX_NOT_READY);
        BizAssert.isTrue(currencyIndex.isReady(), ScannerBizErrCode.CURRENCY_INDEX_NOT_READY);
    }
}
