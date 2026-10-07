package com.nb.tron.scanner.mq.publisher;

import com.nb.chain.client.constant.ChainKafkaTopics;
import com.nb.chain.client.event.ObservedBlockEvent;
import com.nb.core.exception.BizException;
import com.nb.kafka.core.KafkaPublishResult;
import com.nb.kafka.core.KafkaPublisher;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronDepositEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        publisher.publishAndWait(blockData(), List.of(deposit()));

        ArgumentCaptor<ObservedBlockEvent> eventCaptor = ArgumentCaptor.captor();
        verify(kafkaPublisher).publish(
            eq(ChainKafkaTopics.DEPOSIT_DISCOVERED),
            eq("TRON:MAINNET"),
            eventCaptor.capture());
        ObservedBlockEvent blockEvent = eventCaptor.getValue();
        assertThat(blockEvent.getBlockNumber()).isEqualTo(100L);
        assertThat(blockEvent.getBlockHash()).isEqualTo("block-100");
        assertThat(blockEvent.getDeposits()).hasSize(1);
        assertThat(blockEvent.getDeposits().getFirst().getTxId()).isEqualTo("tx-1");
        assertThat(blockEvent.getDeposits().getFirst().getTokenStandard()).isEqualTo("TRC20");
        assertThat(blockEvent.getDeposits().getFirst().getRawAmount())
            .isEqualTo(BigInteger.valueOf(1_000_000L));
    }

    @Test
    void shouldPublishNativeCurrencyWithNativeStandard() {
        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        DepositDiscoveryPublisher publisher = new DepositDiscoveryPublisher(new TronScannerProperties(), kafkaPublisher);
        when(kafkaPublisher.publish(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(new KafkaPublishResult(
                ChainKafkaTopics.DEPOSIT_DISCOVERED, 0, 10L, 100L)));
        TronDepositEvent nativeDeposit = new TronDepositEvent(
            "TRON", "MAINNET", "TRX", "", "tx-trx", -1, 100L, "block-100",
            Instant.parse("2026-10-03T12:00:00Z"), "TSender", "TReceiver", BigInteger.valueOf(1_000_000L));

        publisher.publishAndWait(blockData(), List.of(nativeDeposit));

        ArgumentCaptor<ObservedBlockEvent> eventCaptor = ArgumentCaptor.captor();
        verify(kafkaPublisher).publish(anyString(), anyString(), eventCaptor.capture());
        assertThat(eventCaptor.getValue().getDeposits().getFirst().getTokenStandard()).isEqualTo("NATIVE");
        assertThat(eventCaptor.getValue().getDeposits().getFirst().getContractAddress()).isEmpty();
    }

    @Test
    void shouldFailWhenBrokerAckTimesOut() {
        TronScannerProperties scannerProperties = new TronScannerProperties();
        scannerProperties.setKafkaAckTimeout(Duration.ofMillis(20));
        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        DepositDiscoveryPublisher publisher = new DepositDiscoveryPublisher(scannerProperties, kafkaPublisher);
        when(kafkaPublisher.publish(anyString(), anyString(), any()))
            .thenReturn(new CompletableFuture<>());

        assertThatThrownBy(() -> publisher.publishAndWait(blockData(), List.of(deposit())))
            .isInstanceOf(BizException.class)
            .hasCauseInstanceOf(TimeoutException.class)
            .hasMessageContaining("100")
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_KAFKA_PUBLISH_FAILED);
    }

    @Test
    void shouldKeepProducerFailureAsCause() {
        TronScannerProperties scannerProperties = new TronScannerProperties();
        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        DepositDiscoveryPublisher publisher = new DepositDiscoveryPublisher(scannerProperties, kafkaPublisher);
        RuntimeException failure = new RuntimeException("Broker unavailable");
        when(kafkaPublisher.publish(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.failedFuture(failure));

        assertThatThrownBy(() -> publisher.publishAndWait(blockData(), List.of(deposit())))
            .isInstanceOf(BizException.class)
            .hasCause(failure)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_KAFKA_PUBLISH_FAILED);
    }

    @Test
    void shouldStopWaitingWhenJobThreadIsInterrupted() {
        TronScannerProperties scannerProperties = new TronScannerProperties();
        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        DepositDiscoveryPublisher publisher = new DepositDiscoveryPublisher(scannerProperties, kafkaPublisher);
        when(kafkaPublisher.publish(anyString(), anyString(), any()))
            .thenReturn(new CompletableFuture<>());

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> publisher.publishAndWait(blockData(), List.of(deposit())))
                .isInstanceOf(BizException.class)
                .hasCauseInstanceOf(InterruptedException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ScannerBizErrCode.HEAD_SCAN_KAFKA_PUBLISH_FAILED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
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
