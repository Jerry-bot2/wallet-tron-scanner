package com.nb.tron.scanner.biz;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nb.chain.client.enums.AddressPurpose;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.sdk.enums.TronTokenStandard;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class DepositDiscoveryTrxTest {

    private static final String FROM_HEX =
        "410000000000000000000000000000000000000000";

    private static final String TO_HEX =
        "41a614f803b6fd780986a42c78ec9c7f77e6ded13c";

    private static final String TO_BASE58 =
        "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

    private TronAddressIndex addressIndex;

    private TronCurrencyIndex currencyIndex;

    private DepositDiscoveryService discoveryService;

    @BeforeEach
    void setUp() {
        addressIndex = mock(TronAddressIndex.class);
        currencyIndex = new TronCurrencyIndex();
        currencyIndex.replaceAll(List.of(new TronCurrencyConfig(
            "TRX",
            TronTokenStandard.NATIVE,
            "",
            6)));
        discoveryService = new DepositDiscoveryService(new com.nb.tron.sdk.parser.TronBlockParser(new ObjectMapper()),
            addressIndex, currencyIndex);
    }

    @Test
    void shouldParseSuccessfulTransferToDepositAddress() {
        when(addressIndex.findPurpose(TO_BASE58)).thenReturn(AddressPurpose.DEPOSIT);
        TronTransaction transaction = transaction("SUCCESS", 1_000_000L);

        List<TronDepositEvent> events = discoveryService.discover(block(transaction));

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.currency()).isEqualTo("TRX");
            assertThat(event.contractAddress()).isEmpty();
            assertThat(event.txId()).isEqualTo("tx-1");
            assertThat(event.eventIndex()).isEqualTo(-1);
            assertThat(event.blockNumber()).isEqualTo(100L);
            assertThat(event.blockHash()).isEqualTo("block-100");
            assertThat(event.toAddress()).isEqualTo(TO_BASE58);
            assertThat(event.rawAmount()).isEqualTo(BigInteger.valueOf(1_000_000L));
        });
    }

    @Test
    void shouldIgnoreFailedTransferAndNonDepositAddress() {
        TronTransaction failedTransaction = transaction("REVERT", 1_000_000L);
        assertThat(discoveryService.discover(block(failedTransaction))).isEmpty();

        TronTransaction externalTransaction = transaction("SUCCESS", 1_000_000L);
        when(addressIndex.findPurpose(TO_BASE58)).thenReturn(null);
        assertThat(discoveryService.discover(block(externalTransaction))).isEmpty();
    }

    @Test
    void shouldRejectInvalidTransferAmount() {
        TronTransaction transaction = transaction("SUCCESS", 0L);

        assertThatThrownBy(() -> discoveryService.discover(block(transaction)))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_SDK_DATA_INVALID);
    }

    private TronTransaction transaction(String result, long amount) {
        String rawJson = """
            {
              "txID":"tx-1",
              "raw_data":{"contract":[{
                "type":"TransferContract",
                "parameter":{"value":{
                  "owner_address":"%s",
                  "to_address":"%s",
                  "amount":%d
                }}
              }]},
              "ret":[{"contractRet":"%s"}]
            }
            """.formatted(FROM_HEX, TO_HEX, amount, result);
        return new TronTransaction("tx-1", rawJson);
    }

    private TronBlockData block(TronTransaction transaction) {
        return new TronBlockData(
            "full-primary",
            100L,
            "block-100",
            "block-99",
            Instant.parse("2026-10-03T10:00:00Z"),
            List.of(transaction),
            Map.of(transaction.transactionId(), new com.nb.tron.sdk.model.TronTransactionReceipt(transaction.transactionId(), 100L, "{}")));
    }
}
