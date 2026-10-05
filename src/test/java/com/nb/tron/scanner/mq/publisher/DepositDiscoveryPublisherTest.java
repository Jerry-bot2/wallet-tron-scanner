package com.nb.tron.scanner.mq.publisher;

import com.nb.chain.client.constant.ChainKafkaTopics;
import com.nb.chain.client.event.ObservedBlockEvent;
import com.nb.kafka.core.KafkaPublishResult;
import com.nb.kafka.core.KafkaPublisher;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronDepositEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class DepositDiscoveryPublisherTest {

    @Test
    void shouldPublishBlockDepositsWithStableMessageKey() {
        TronScannerProperties scannerProperties = new TronScannerProperties();
        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        DepositDiscoveryPublisher publisher = new DepositDiscoveryPublisher(
            scannerProperties,
            kafkaPublisher);
        KafkaPublishResult publishResult = new KafkaPublishResult(
            ChainKafkaTopics.DEPOSIT_DISCOVERED, 0, 10L, 100L);
        when(kafkaPublisher.publish(
            anyString(),
            anyString(),
            any()))
            .thenReturn(CompletableFuture.completedFuture(publishResult));

        CompletableFuture<KafkaPublishResult> result = publisher.publish(blockData(), List.of(deposit()));

        ArgumentCaptor<ObservedBlockEvent> eventCaptor = ArgumentCaptor.captor();
        verify(kafkaPublisher).publish(
            eq(ChainKafkaTopics.DEPOSIT_DISCOVERED),
            eq("TRON:MAINNET"),
            eventCaptor.capture());
        ObservedBlockEvent blockEvent = eventCaptor.getValue();
        assertThat(result.join()).isSameAs(publishResult);
        assertThat(blockEvent.getBlockNumber()).isEqualTo(100L);
        assertThat(blockEvent.getBlockHash()).isEqualTo("block-100");
        assertThat(blockEvent.getDeposits()).hasSize(1);
        assertThat(blockEvent.getDeposits().getFirst().getTxId()).isEqualTo("tx-1");
        assertThat(blockEvent.getDeposits().getFirst().getRawAmount())
            .isEqualTo(BigInteger.valueOf(1_000_000L));
    }

    private TronBlockData blockData() {
        return new TronBlockData(
            "full-primary",
            100L,
            "block-100",
            "block-99",
            Instant.parse("2026-10-03T12:00:00Z"),
            List.of(),
            Map.of());
    }

    private TronDepositEvent deposit() {
        return new TronDepositEvent(
            "TRON",
            "MAINNET",
            "USDT",
            "TUsdtContract",
            "tx-1",
            0,
            100L,
            "block-100",
            Instant.parse("2026-10-03T12:00:00Z"),
            "TSender",
            "TReceiver",
            BigInteger.valueOf(1_000_000L));
    }
}
