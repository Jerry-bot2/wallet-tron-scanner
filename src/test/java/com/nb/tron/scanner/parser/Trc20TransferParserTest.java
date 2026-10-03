package com.nb.tron.scanner.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nb.chain.client.enums.AddressPurpose;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronTokenStandard;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.model.TronTransaction;
import com.nb.tron.scanner.model.TronTransactionReceipt;
import com.nb.tron.scanner.support.JsonCodec;
import com.nb.tron.scanner.support.TronAddressCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.nb.tron.scanner.constant.TronTransactionConstants.TRC20_TRANSFER_EVENT_TOPIC;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class Trc20TransferParserTest {

    private static final String USDT_CONTRACT_HEX =
        "a614f803b6fd780986a42c78ec9c7f77e6ded13c";

    private static final String USDT_CONTRACT_BASE58 =
        "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

    private static final String FROM_TOPIC =
        "0000000000000000000000000000000000000000000000000000000000000001";

    private static final String TO_TOPIC =
        "000000000000000000000000a614f803b6fd780986a42c78ec9c7f77e6ded13c";

    private static final String TO_BASE58 = USDT_CONTRACT_BASE58;

    private static final String ONE_USDT_RAW =
        "00000000000000000000000000000000000000000000000000000000000f4240";

    private TronAddressIndex addressIndex;

    private TronAddressCodec addressCodec;

    private Trc20TransferParser transferParser;

    @BeforeEach
    void setUp() {
        addressIndex = mock(TronAddressIndex.class);
        addressCodec = new TronAddressCodec();
        TronCurrencyIndex currencyIndex = new TronCurrencyIndex();
        currencyIndex.replaceAll(List.of(new TronCurrencyConfig(
            "USDT",
            TronTokenStandard.TRC20,
            USDT_CONTRACT_BASE58,
            6)));
        transferParser = new Trc20TransferParser(
            new JsonCodec(new ObjectMapper()),
            addressCodec,
            addressIndex,
            currencyIndex,
            new TronScannerProperties());
    }

    @Test
    void shouldParseTransferToDepositAddress() {
        when(addressIndex.findPurpose(TO_BASE58)).thenReturn(AddressPurpose.DEPOSIT);
        TronTransaction transaction = transaction("SUCCESS");
        TronTransactionReceipt receipt = receipt("SUCESS", transferLog(USDT_CONTRACT_HEX));

        List<TronDepositEvent> events = transferParser.parse(block(transaction), transaction, receipt);

        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.chainCode()).isEqualTo("TRON");
            assertThat(event.chainNetwork()).isEqualTo("MAINNET");
            assertThat(event.currency()).isEqualTo("USDT");
            assertThat(event.contractAddress()).isEqualTo(USDT_CONTRACT_BASE58);
            assertThat(event.txId()).isEqualTo("tx-1");
            assertThat(event.eventIndex()).isZero();
            assertThat(event.blockNumber()).isEqualTo(100L);
            assertThat(event.blockHash()).isEqualTo("block-100");
            assertThat(event.fromAddress()).isEqualTo(addressCodec.fromTopic(FROM_TOPIC));
            assertThat(event.toAddress()).isEqualTo(TO_BASE58);
            assertThat(event.rawAmount()).isEqualTo(BigInteger.valueOf(1_000_000L));
        });
    }

    @Test
    void shouldKeepOriginalLogIndexWhenOtherEventsExist() {
        when(addressIndex.findPurpose(TO_BASE58)).thenReturn(AddressPurpose.DEPOSIT);
        TronTransaction transaction = transaction("SUCCESS");
        String approvalLog = """
            {
              "address":"%s",
              "topics":["8c5be1e5ebec7d5bd14f714f5a4e64f3a0cdbd8f6c3c4f5d6b3a4c5d6e7f8091"],
              "data":""
            }
            """.formatted(USDT_CONTRACT_HEX);
        TronTransactionReceipt receipt = receipt(
            "SUCESS",
            approvalLog + "," + transferLog(USDT_CONTRACT_HEX));

        List<TronDepositEvent> events = transferParser.parse(block(transaction), transaction, receipt);

        assertThat(events).singleElement()
            .extracting(TronDepositEvent::eventIndex)
            .isEqualTo(1);
    }

    @Test
    void shouldParseMultipleTransferLogsFromOneTransaction() {
        when(addressIndex.findPurpose(TO_BASE58)).thenReturn(AddressPurpose.DEPOSIT);
        TronTransaction transaction = transaction("SUCCESS");
        TronTransactionReceipt receipt = receipt(
            "SUCESS",
            transferLog(USDT_CONTRACT_HEX) + "," + transferLog(USDT_CONTRACT_HEX));

        List<TronDepositEvent> events = transferParser.parse(block(transaction), transaction, receipt);

        assertThat(events)
            .extracting(TronDepositEvent::eventIndex)
            .containsExactly(0, 1);
    }

    @Test
    void shouldIgnoreUnknownContractAndNonDepositAddress() {
        TronTransaction transaction = transaction("SUCCESS");
        String unknownContract = "1111111111111111111111111111111111111111";
        TronTransactionReceipt unknownReceipt = receipt("SUCESS", transferLog(unknownContract));
        assertThat(transferParser.parse(block(transaction), transaction, unknownReceipt)).isEmpty();

        when(addressIndex.findPurpose(TO_BASE58)).thenReturn(null);
        TronTransactionReceipt externalReceipt = receipt("SUCESS", transferLog(USDT_CONTRACT_HEX));
        assertThat(transferParser.parse(block(transaction), transaction, externalReceipt)).isEmpty();
    }

    @Test
    void shouldIgnoreFailedTransaction() {
        TronTransaction failedTransaction = transaction("REVERT");
        TronTransactionReceipt receipt = receipt("SUCESS", transferLog(USDT_CONTRACT_HEX));
        assertThat(transferParser.parse(block(failedTransaction), failedTransaction, receipt)).isEmpty();

        TronTransaction successfulTransaction = transaction("SUCCESS");
        TronTransactionReceipt failedReceipt = receipt("FAILED", transferLog(USDT_CONTRACT_HEX));
        assertThat(transferParser.parse(block(successfulTransaction), successfulTransaction, failedReceipt)).isEmpty();
    }

    @Test
    void shouldRejectInvalidTransferAmount() {
        TronTransaction transaction = transaction("SUCCESS");
        String invalidTransferLog = transferLog(USDT_CONTRACT_HEX)
            .replace(ONE_USDT_RAW, "01");
        TronTransactionReceipt receipt = receipt("SUCESS", invalidTransferLog);

        assertThatThrownBy(() -> transferParser.parse(block(transaction), transaction, receipt))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_TRANSACTION_INVALID);
    }

    private TronTransaction transaction(String result) {
        String rawJson = """
            {
              "txID":"tx-1",
              "raw_data":{"contract":[{"type":"TriggerSmartContract"}]},
              "ret":[{"contractRet":"%s"}]
            }
            """.formatted(result);
        return new TronTransaction("tx-1", rawJson);
    }

    private TronTransactionReceipt receipt(String result, String logs) {
        String rawJson = """
            {
              "id":"tx-1",
              "blockNumber":100,
              "result":"%s",
              "log":[%s]
            }
            """.formatted(result, logs);
        return new TronTransactionReceipt("tx-1", 100L, rawJson);
    }

    private String transferLog(String contractAddress) {
        return """
            {
              "address":"%s",
              "topics":["%s","%s","%s"],
              "data":"%s"
            }
            """.formatted(
            contractAddress,
            TRC20_TRANSFER_EVENT_TOPIC,
            FROM_TOPIC,
            TO_TOPIC,
            ONE_USDT_RAW);
    }

    private TronBlockData block(TronTransaction transaction) {
        return new TronBlockData(
            "full-primary",
            100L,
            "block-100",
            "block-99",
            Instant.parse("2026-10-03T10:00:00Z"),
            List.of(transaction),
            Map.of());
    }
}
