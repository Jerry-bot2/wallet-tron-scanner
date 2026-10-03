package com.nb.tron.scanner.parser;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.model.TronTransaction;
import com.nb.tron.scanner.model.TronTransactionReceipt;
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
class TronBlockParserTest {

    private TrxTransferParser trxTransferParser;

    private Trc20TransferParser trc20TransferParser;

    private TronBlockParser blockParser;

    @BeforeEach
    void setUp() {
        trxTransferParser = mock(TrxTransferParser.class);
        trc20TransferParser = mock(Trc20TransferParser.class);
        blockParser = new TronBlockParser(trxTransferParser, trc20TransferParser);
    }

    @Test
    void shouldMergeEventsInTransactionAndLogOrder() {
        TronTransaction firstTransaction = transaction("tx-1");
        TronTransaction secondTransaction = transaction("tx-2");
        TronTransactionReceipt firstReceipt = receipt("tx-1", 100L);
        TronTransactionReceipt secondReceipt = receipt("tx-2", 100L);
        TronBlockData blockData = block(
            List.of(firstTransaction, secondTransaction),
            Map.of("tx-1", firstReceipt, "tx-2", secondReceipt));
        TronDepositEvent trxEvent = event("tx-1", -1, "TRX");
        TronDepositEvent firstUsdtEvent = event("tx-2", 0, "USDT");
        TronDepositEvent secondUsdtEvent = event("tx-2", 2, "USDT");

        when(trxTransferParser.parse(blockData, firstTransaction)).thenReturn(List.of(trxEvent));
        when(trc20TransferParser.parse(blockData, firstTransaction, firstReceipt)).thenReturn(List.of());
        when(trxTransferParser.parse(blockData, secondTransaction)).thenReturn(List.of());
        when(trc20TransferParser.parse(blockData, secondTransaction, secondReceipt))
            .thenReturn(List.of(firstUsdtEvent, secondUsdtEvent));

        List<TronDepositEvent> events = blockParser.parse(blockData);

        assertThat(events).containsExactly(trxEvent, firstUsdtEvent, secondUsdtEvent);
    }

    @Test
    void shouldRejectMissingOrMismatchedReceipt() {
        TronTransaction transaction = transaction("tx-1");
        TronBlockData missingReceiptBlock = block(List.of(transaction), Map.of());
        assertIncompleteBlock(missingReceiptBlock);

        TronTransactionReceipt wrongHeightReceipt = receipt("tx-1", 99L);
        TronBlockData wrongHeightBlock = block(
            List.of(transaction),
            Map.of("tx-1", wrongHeightReceipt));
        assertIncompleteBlock(wrongHeightBlock);
    }

    @Test
    void shouldPropagateTransactionParsingFailure() {
        TronTransaction transaction = transaction("tx-1");
        TronTransactionReceipt receipt = receipt("tx-1", 100L);
        TronBlockData blockData = block(List.of(transaction), Map.of("tx-1", receipt));
        BizException parsingFailure = BizException.of(ScannerBizErrCode.TRON_TRANSACTION_INVALID);
        when(trxTransferParser.parse(blockData, transaction)).thenThrow(parsingFailure);

        assertThatThrownBy(() -> blockParser.parse(blockData))
            .isSameAs(parsingFailure);
    }

    private void assertIncompleteBlock(TronBlockData blockData) {
        assertThatThrownBy(() -> blockParser.parse(blockData))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_BLOCK_DATA_INCOMPLETE);
    }

    private TronTransaction transaction(String transactionId) {
        return new TronTransaction(transactionId, "{}");
    }

    private TronTransactionReceipt receipt(String transactionId, long blockHeight) {
        return new TronTransactionReceipt(transactionId, blockHeight, "{}");
    }

    private TronBlockData block(List<TronTransaction> transactions,
                                Map<String, TronTransactionReceipt> receipts) {
        return new TronBlockData(
            "full-primary",
            100L,
            "block-100",
            "block-99",
            Instant.parse("2026-10-03T10:00:00Z"),
            transactions,
            receipts);
    }

    private TronDepositEvent event(String transactionId,
                                   int eventIndex,
                                   String currency) {
        return new TronDepositEvent(
            "TRON",
            "MAINNET",
            currency,
            "",
            transactionId,
            eventIndex,
            100L,
            "block-100",
            Instant.parse("2026-10-03T10:00:00Z"),
            "from-address",
            "to-address",
            BigInteger.ONE);
    }
}
