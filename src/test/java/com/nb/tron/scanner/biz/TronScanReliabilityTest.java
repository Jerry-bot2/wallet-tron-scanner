package com.nb.tron.scanner.biz;

import com.nb.chain.client.event.DepositConfirmedEvent;
import com.nb.kafka.core.KafkaPublishResult;
import com.nb.kafka.core.KafkaPublisher;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.mq.publisher.DepositConfirmedPublisher;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronNodeHeight;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static com.nb.chain.client.constant.ChainKafkaTopics.DEPOSIT_CONFIRMED_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 验收Kafka已确认、数据库推进失败后的安全重试边界。
 * <p>
 * Author: bin jack
 * Date: 22.10.26
 */
class TronScanReliabilityTest {

    @Test
    void shouldRepublishSameChainEventWhenProgressCommitFailsAfterKafkaAck() {
        TronScanCheckpoint checkpoint = checkpoint(99L, "block-99");
        TronBlockData block = block();
        TronDepositEvent deposit = deposit();

        TronAddressIndex addressIndex = mock(TronAddressIndex.class);
        TronCurrencyIndex currencyIndex = mock(TronCurrencyIndex.class);
        when(addressIndex.isReady()).thenReturn(true);
        when(currencyIndex.isReady()).thenReturn(true);

        TronNodeManager nodeManager = mock(TronNodeManager.class);
        when(nodeManager.getSolidHeight()).thenReturn(
            new TronNodeHeight("solidity-primary", 100L, "block-100", block.blockTimestamp()));
        when(nodeManager.getBlockDataByHeight(100L)).thenReturn(block);

        DepositDiscoveryService discoveryService = mock(DepositDiscoveryService.class);
        when(discoveryService.discover(block)).thenReturn(List.of(deposit));

        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        when(kafkaPublisher.publish(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(new KafkaPublishResult(
                DEPOSIT_CONFIRMED_TOPIC, 0, 10L, 100L)));
        DepositConfirmedPublisher publisher = new DepositConfirmedPublisher(
            new TronScannerProperties(), kafkaPublisher);

        HeadBlockContinuityService continuityService = mock(HeadBlockContinuityService.class);
        HeadScanProgressService progressService = mock(HeadScanProgressService.class);
        when(progressService.loadCheckpoint()).thenReturn(checkpoint);
        RuntimeException databaseFailure = new RuntimeException("database unavailable");
        when(progressService.advance(checkpoint, block))
            .thenThrow(databaseFailure)
            .thenReturn(checkpoint(100L, "block-100"));

        HeadBlockScanService scanService = new HeadBlockScanService(
            new TronScannerProperties(), addressIndex, currencyIndex, nodeManager,
            discoveryService, publisher, continuityService, progressService);

        // 1. Kafka已经ACK，但数据库推进失败，本轮必须报错并保留原检查点。
        assertThatThrownBy(scanService::scanBlocks).isSameAs(databaseFailure);

        // 2. 下轮从原检查点重扫同一区块，重新发布后再完成数据库推进。
        assertThat(scanService.scanBlocks()).isEqualTo(1);

        // 3. 两次事件身份和内容完全一致，Chain Server可按chainCode + txId + eventIndex幂等接收。
        ArgumentCaptor<DepositConfirmedEvent> eventCaptor = ArgumentCaptor.captor();
        verify(kafkaPublisher, times(2)).publish(anyString(), anyString(), eventCaptor.capture());
        assertThat(eventCaptor.getAllValues())
            .extracting(
                DepositConfirmedEvent::getChainCode,
                event -> event.getDeposits().getFirst().getTxId(),
                event -> event.getDeposits().getFirst().getEventIndex())
            .containsExactly(
                tuple("TRON", deposit.txId(), deposit.eventIndex()),
                tuple("TRON", deposit.txId(), deposit.eventIndex()));
        assertThat(eventCaptor.getAllValues().get(1))
            .usingRecursiveComparison()
            .isEqualTo(eventCaptor.getAllValues().getFirst());
        verify(progressService, times(2)).advance(checkpoint, block);
    }

    private TronScanCheckpoint checkpoint(long blockNumber, String blockHash) {
        return new TronScanCheckpoint()
            .setChainNetwork("MAINNET")
            .setLastBlockNumber(blockNumber)
            .setLastBlockHash(blockHash);
    }

    private TronBlockData block() {
        return new TronBlockData(
            "full-primary",
            100L,
            "block-100",
            "block-99",
            Instant.parse("2026-10-22T10:00:00Z"),
            List.of(),
            Map.of());
    }

    private TronDepositEvent deposit() {
        return new TronDepositEvent(
            "USDT",
            "TUsdtContract",
            "tx-1",
            0,
            100L,
            "block-100",
            Instant.parse("2026-10-22T10:00:00Z"),
            "TSender",
            "TReceiver",
            BigInteger.valueOf(1_000_000L));
    }
}
