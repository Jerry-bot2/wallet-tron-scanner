package com.nb.tron.scanner.mq.publisher;

import com.nb.chain.client.constant.ChainKafkaTopics;
import com.nb.chain.client.event.DepositDiscoveryEvent;
import com.nb.chain.client.event.ObservedBlockEvent;
import com.nb.kafka.core.KafkaPublishResult;
import com.nb.kafka.core.KafkaPublisher;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronDepositEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 充值发现消息发布器
 *
 * <p>负责把 Scanner 内部解析模型转换成 chain-client 公共事件，并按链和网络
 * 使用固定消息 Key 发送到 Kafka。</p>
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
     * 发布一个区块内发现的全部充值
     *
     * @param blockData 当前区块数据
     * @param deposits  当前区块识别出的充值事实
     * @return Broker 确认结果；调用方必须等待完成后才能推进扫描检查点
     */
    public CompletableFuture<KafkaPublishResult> publish(TronBlockData blockData, List<TronDepositEvent> deposits) {
        ObservedBlockEvent blockEvent = toBlockEvent(blockData, deposits);
        return kafkaPublisher.publish(
            ChainKafkaTopics.DEPOSIT_DISCOVERED,
            blockEvent.messageKey(),
            blockEvent);
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
