package com.nb.tron.scanner.index;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.enums.TronTokenStandard;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class TronCurrencyIndexTest {

    @Test
    void shouldRejectReadsBeforeIndexIsReady() {
        TronCurrencyIndex currencyIndex = new TronCurrencyIndex();

        assertThat(currencyIndex.isReady()).isFalse();
        assertThatThrownBy(currencyIndex::findNativeCurrency)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.CURRENCY_INDEX_NOT_READY);
    }

    @Test
    void shouldReplaceCompleteCurrencySnapshot() {
        TronCurrencyIndex currencyIndex = new TronCurrencyIndex();
        TronCurrencyConfig trx = currency("TRX", TronTokenStandard.NATIVE, "", 6);
        TronCurrencyConfig usdt = currency("USDT", TronTokenStandard.TRC20, "TUsdtContract", 6);

        currencyIndex.replaceAll(List.of(trx, usdt));

        assertThat(currencyIndex.isReady()).isTrue();
        assertThat(currencyIndex.size()).isEqualTo(2);
        assertThat(currencyIndex.findNativeCurrency()).isEqualTo(trx);
        assertThat(currencyIndex.findByContractAddress("TUsdtContract")).isEqualTo(usdt);
        assertThat(currencyIndex.findByContractAddress("TUnknownContract")).isNull();
    }

    @Test
    void shouldKeepOldSnapshotWhenNewSnapshotContainsDuplicateContract() {
        TronCurrencyIndex currencyIndex = new TronCurrencyIndex();
        TronCurrencyConfig oldUsdt = currency("USDT", TronTokenStandard.TRC20, "TOldContract", 6);
        currencyIndex.replaceAll(List.of(oldUsdt));

        assertThatThrownBy(() -> currencyIndex.replaceAll(List.of(
            currency("USDT", TronTokenStandard.TRC20, "TNewContract", 6),
            currency("USDC", TronTokenStandard.TRC20, "TNewContract", 6))))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.CURRENCY_CONFIG_DUPLICATE);

        assertThat(currencyIndex.size()).isEqualTo(1);
        assertThat(currencyIndex.findByContractAddress("TOldContract")).isEqualTo(oldUsdt);
        assertThat(currencyIndex.findByContractAddress("TNewContract")).isNull();
    }

    private TronCurrencyConfig currency(String currency,
                                        TronTokenStandard tokenStandard,
                                        String contractAddress,
                                        int decimals) {
        return new TronCurrencyConfig(currency, tokenStandard, contractAddress, decimals);
    }
}
