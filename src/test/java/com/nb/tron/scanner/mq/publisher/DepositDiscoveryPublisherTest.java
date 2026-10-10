package com.nb.tron.scanner.mq.publisher;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.nb.chain.client.constant.ChainKafkaTopics;
import com.nb.chain.client.event.ObservedBlockEvent;
import com.nb.core.exception.BizException;
import com.nb.kafka.core.KafkaJsonMessageConverter;
import com.nb.kafka.core.KafkaPublishResult;
import com.nb.kafka.core.KafkaPublisher;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.sdk.model.TronBlockData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.support.MessageBuilder;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class DepositDiscoveryPublisherTest {

    @ParameterizedTest
    @CsvSource({
        "0, 0, 0",
        "1, 1, 1",
        "299, 1, 299",
        "300, 1, 300",
        "301, 2, 1",
        "600, 2, 300",
        "601, 3, 1",
        "650, 3, 50"
    })
    void shouldSplitDepositsIntoMessagesOfAtMost300(int depositCount, int messageCount, int lastMessageSize) {
        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        DepositDiscoveryPublisher publisher = new DepositDiscoveryPublisher(new TronScannerProperties(), kafkaPublisher);
        when(kafkaPublisher.publish(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(new KafkaPublishResult(
                ChainKafkaTopics.DEPOSIT_DISCOVERED_TOPIC, 0, 10L, 100L)));
        List<TronDepositEvent> deposits = IntStream.range(0, depositCount).mapToObj(this::deposit).toList();

        publisher.publishAndWait(blockData(), deposits);

        if (messageCount == 0) {
            verifyNoInteractions(kafkaPublisher);
            return;
        }
        ArgumentCaptor<ObservedBlockEvent> eventCaptor = ArgumentCaptor.captor();
        verify(kafkaPublisher, times(messageCount)).publish(
            eq(ChainKafkaTopics.DEPOSIT_DISCOVERED_TOPIC), eq("TRON"), eventCaptor.capture());
        List<ObservedBlockEvent> messages = eventCaptor.getAllValues();
        assertThat(messages.getLast().getDeposits()).hasSize(lastMessageSize);
        assertThat(messages.subList(0, messages.size() - 1))
            .allSatisfy(message -> assertThat(message.getDeposits()).hasSize(300));
        assertThat(messages).allSatisfy(message -> {
            assertThat(message.getChainCode()).isEqualTo("TRON");
            assertThat(message.getBlockNumber()).isEqualTo(100L);
            assertThat(message.getBlockHash()).isEqualTo("block-100");
            assertThat(message.getParentBlockHash()).isEqualTo("block-99");
            assertThat(message.getBlockTimestamp()).isEqualTo(blockData().blockTimestamp());
        });
        // 拆分后还原整块：顺序不变、不漏充值，也不重复添加。
        assertThat(messages.stream().flatMap(message -> message.getDeposits().stream())
            .map(event -> event.getTxId()).toList())
            .containsExactlyElementsOf(deposits.stream().map(TronDepositEvent::txId).toList());
    }

    @Test
    void shouldKeep300LongFieldDepositsBelow150KiB() {
        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        DepositDiscoveryPublisher publisher = new DepositDiscoveryPublisher(new TronScannerProperties(), kafkaPublisher);
        when(kafkaPublisher.publish(anyString(), anyString(), any()))
            .thenReturn(CompletableFuture.completedFuture(new KafkaPublishResult(
                ChainKafkaTopics.DEPOSIT_DISCOVERED_TOPIC, 0, 10L, 100L)));
        TronBlockData blockData = new TronBlockData("full-primary", Long.MAX_VALUE,
            "a".repeat(64), "b".repeat(64), Instant.parse("2026-10-07T12:00:00.123456789Z"), List.of(), Map.of());
        // 使用较长的正常字段范围：32 位币种编码、64 位交易 ID、uint256 最大金额。
        List<TronDepositEvent> deposits = IntStream.range(0, 300).mapToObj(index -> new TronDepositEvent(
            "TRON", "MAINNET", "X".repeat(32), "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t",
            "%064x".formatted(index), Integer.MAX_VALUE, blockData.blockHeight(), blockData.blockId(),
            blockData.blockTimestamp(), "T" + "a".repeat(33), "T" + "b".repeat(33),
            BigInteger.TWO.pow(256).subtract(BigInteger.ONE))).toList();

        publisher.publishAndWait(blockData, deposits);

        ArgumentCaptor<ObservedBlockEvent> eventCaptor = ArgumentCaptor.captor();
        verify(kafkaPublisher).publish(anyString(), anyString(), eventCaptor.capture());
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        KafkaJsonMessageConverter converter = new KafkaJsonMessageConverter(objectMapper);
        var record = converter.fromMessage(MessageBuilder.withPayload(eventCaptor.getValue()).build(),
            ChainKafkaTopics.DEPOSIT_DISCOVERED_TOPIC);
        byte[] messageBytes = ((String) record.value()).getBytes(StandardCharsets.UTF_8);
        assertThat(messageBytes.length).isLessThan(150 * 1024);
    }

    @Test
    void shouldPublishBlockDepositsWithStableMessageKey() {
        TronScannerProperties scannerProperties = new TronScannerProperties();
        KafkaPublisher kafkaPublisher = mock(KafkaPublisher.class);
        DepositDiscoveryPublisher publisher = new DepositDiscoveryPublisher(
            scannerProperties,
            kafkaPublisher);
        KafkaPublishResult publishResult = new KafkaPublishResult(
            ChainKafkaTopics.DEPOSIT_DISCOVERED_TOPIC, 0, 10L, 100L);
        when(kafkaPublisher.publish(
            anyString(),
            anyString(),
            any()))
            .thenReturn(CompletableFuture.completedFuture(publishResult));

        publisher.publishAndWait(blockData(), List.of(deposit()));

        ArgumentCaptor<ObservedBlockEvent> eventCaptor = ArgumentCaptor.captor();
        verify(kafkaPublisher).publish(
            eq(ChainKafkaTopics.DEPOSIT_DISCOVERED_TOPIC),
            eq("TRON"),
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
                ChainKafkaTopics.DEPOSIT_DISCOVERED_TOPIC, 0, 10L, 100L)));
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
        return deposit(1);
    }

    private TronDepositEvent deposit(int index) {
        return new TronDepositEvent(
            "TRON",
            "MAINNET",
            "USDT",
            "TUsdtContract",
            "tx-" + index,
            0,
            100L,
            "block-100",
            Instant.parse("2026-10-03T12:00:00Z"),
            "TSender",
            "TReceiver",
            BigInteger.valueOf(1_000_000L));
    }
}
