package com.nb.tron.scanner.mq.publisher;

import com.nb.chain.client.constant.ChainKafkaTopics;
import com.nb.chain.client.event.DepositDiscoveryEvent;
import com.nb.chain.client.event.ObservedBlockEvent;
import com.nb.core.exception.BizException;
import com.nb.kafka.core.KafkaPublisher;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronDepositEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 充值发现消息发布器
 *
 * <p>把 Scanner 内部解析模型转换成 chain-client 公共事件，按链和网络
 * 使用固定消息 Key 发送到 Kafka，并等待 Broker 确认。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
@RequiredArgsConstructor
public class DepositDiscoveryPublisher {

    private final TronScannerProperties scannerProperties;

    private final KafkaPublisher kafkaPublisher;

    /**
     * 发送一个区块内发现的全部充值，并等待 Broker 确认。
     * 发送失败或等待超时会抛出异常，调用方不能推进扫描检查点。
     *
     * @param blockData 当前区块数据
     * @param deposits  当前区块识别出的充值事实
     */
    public void publishAndWait(TronBlockData blockData, List<TronDepositEvent> deposits) {
        ObservedBlockEvent blockEvent = toBlockEvent(blockData, deposits);
        try {
            kafkaPublisher.publish(ChainKafkaTopics.DEPOSIT_DISCOVERED, blockEvent.messageKey(), blockEvent)
                .get(scannerProperties.getKafkaAckTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw publishFailed(blockData, exception);
        } catch (ExecutionException exception) {
            throw publishFailed(blockData, exception.getCause());
        } catch (TimeoutException | RuntimeException exception) {
            throw publishFailed(blockData, exception);
        }
    }

    private BizException publishFailed(TronBlockData blockData, Throwable cause) {
        return new BizException(
            ScannerBizErrCode.HEAD_SCAN_KAFKA_PUBLISH_FAILED,
            cause,
            blockData.blockHeight());
    }

    private ObservedBlockEvent toBlockEvent(TronBlockData blockData, List<TronDepositEvent> deposits) {
        return new ObservedBlockEvent()
            .setChainCode(scannerProperties.getChainCode())
            .setChainNetwork(scannerProperties.getChainNetwork())
            .setBlockNumber(blockData.blockHeight())
            .setBlockHash(blockData.blockId())
            .setParentBlockHash(blockData.parentBlockId())
            .setBlockTimestamp(blockData.blockTimestamp())
            .setDeposits(deposits.stream().map(this::toDepositEvent).toList());
    }

    private DepositDiscoveryEvent toDepositEvent(TronDepositEvent deposit) {
        return new DepositDiscoveryEvent()
            .setCurrency(deposit.currency())
            .setContractAddress(deposit.contractAddress())
            .setTxId(deposit.txId())
            .setEventIndex(deposit.eventIndex())
            .setFromAddress(deposit.fromAddress())
            .setToAddress(deposit.toAddress())
            .setRawAmount(deposit.rawAmount());
    }
}
