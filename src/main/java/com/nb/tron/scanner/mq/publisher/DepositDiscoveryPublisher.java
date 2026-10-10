package com.nb.tron.scanner.mq.publisher;

import com.nb.chain.client.constant.ChainKafkaTopics;
import com.nb.chain.client.event.DepositDiscoveryEvent;
import com.nb.chain.client.event.ObservedBlockEvent;
import com.nb.core.exception.BizException;
import com.nb.kafka.core.KafkaPublisher;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.constant.TronConstants;
import com.nb.tron.sdk.enums.TronTokenStandard;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.support.HeadScanStatistics;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.nb.tron.scanner.constant.TronConstants.DEPOSIT_MESSAGE_BATCH_SIZE;

/**
 * 充值发现消息发布器
 *
 * <p>把 Scanner 内部解析模型转换成 chain-client 公共事件，按链
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
     * 按每条最多 300 笔发送本区块的全部充值，并等待 Broker 确认。
     *
     * <p>拆分原因：同一区块的充值过多时，单条消息可能超过 Kafka 的大小限制，
     * 导致每次重扫都发送失败，扫描进度一直停在这个区块。
     * 按每条最多 300 笔拆分，为消息大小预留余量。</p>
     *
     * <p>例如 650 笔拆成 300、300、50 三条消息，依次发送并等待 ACK。
     * 全部成功后才正常返回；中途失败立即结束，调用方不能推进扫描检查点，
     * 下轮重扫时重新发送本区块的全部消息。</p>
     *
     * @param blockData 当前区块数据
     * @param deposits  当前区块识别出的充值事实
     */
    public void publishAndWait(TronBlockData blockData, List<TronDepositEvent> deposits) {
        HeadScanStatistics.timeKafkaAck(() -> publishBatches(blockData, deposits));
    }

    private void publishBatches(TronBlockData blockData, List<TronDepositEvent> deposits) {
        // 1. 按原始顺序每 300 笔分一组；最后一组不足 300 笔也正常发送。
        for (int start = 0; start < deposits.size(); start += DEPOSIT_MESSAGE_BATCH_SIZE) {
            int end = Math.min(start + DEPOSIT_MESSAGE_BATCH_SIZE, deposits.size());
            // 2. 当前组收到 ACK 才发送下一组；任一组失败就抛出异常，停止后续发送。
            sendAndWaitForAck(blockData, deposits.subList(start, end));
        }
    }

    private void sendAndWaitForAck(TronBlockData blockData, List<TronDepositEvent> deposits) {
        // 每条消息携带相同的区块信息，deposits 只包含当前组的充值。
        ObservedBlockEvent blockEvent = toBlockEvent(blockData, deposits);
        try {
            kafkaPublisher.publish(ChainKafkaTopics.DEPOSIT_DISCOVERED_TOPIC, blockEvent.messageKey(), blockEvent)
                .get(scannerProperties.getKafkaAckTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            // 任务线程被中断时保留中断标记，结束本轮。
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
            .setChainCode(TronConstants.CHAIN_CODE)
            .setBlockNumber(blockData.blockHeight())
            .setBlockHash(blockData.blockId())
            .setParentBlockHash(blockData.parentBlockId())
            .setBlockTimestamp(blockData.blockTimestamp())
            .setDeposits(deposits.stream().map(this::toDepositEvent).toList());
    }

    private DepositDiscoveryEvent toDepositEvent(TronDepositEvent deposit) {
        // TRON 的标准判断留在 Scanner；链服务按消息中的资产身份匹配配置。
        TronTokenStandard tokenStandard = deposit.contractAddress().isEmpty()
            ? TronTokenStandard.NATIVE : TronTokenStandard.TRC20;
        return new DepositDiscoveryEvent()
            .setCurrency(deposit.currency())
            .setTokenStandard(tokenStandard.getCode())
            .setContractAddress(deposit.contractAddress())
            .setTxId(deposit.txId())
            .setEventIndex(deposit.eventIndex())
            .setFromAddress(deposit.fromAddress())
            .setToAddress(deposit.toAddress())
            .setRawAmount(deposit.rawAmount());
    }
}
