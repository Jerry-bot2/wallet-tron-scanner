package com.nb.tron.scanner.biz;

import com.nb.chain.client.resp.ScannerCurrencyResp;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.client.CurrencySyncClient;
import com.nb.tron.scanner.enums.TronTokenStandard;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class CurrencySyncServiceTest {

    private CurrencySyncClient currencySyncClient;

    private TronCurrencyIndex currencyIndex;

    private CurrencySyncService currencySyncService;

    @BeforeEach
    void setUp() {
        currencySyncClient = mock(CurrencySyncClient.class);
        currencyIndex = new TronCurrencyIndex();
        currencySyncService = new CurrencySyncService(currencySyncClient, currencyIndex);
    }

    @Test
    void shouldSyncCompleteCurrencySnapshot() {
        when(currencySyncClient.pullCurrencies()).thenReturn(List.of(
            currency("TRX", "NATIVE", "", 6),
            currency("USDT", "TRC20", "TUsdtContract", 6)));

        int currencyCount = currencySyncService.syncCurrencies();

        assertThat(currencyCount).isEqualTo(2);
        assertThat(currencyIndex.findNativeCurrency().tokenStandard())
            .isEqualTo(TronTokenStandard.NATIVE);
        assertThat(currencyIndex.findByContractAddress("TUsdtContract").currency())
            .isEqualTo("USDT");
    }

    @Test
    void shouldKeepOldSnapshotWhenNewConfigurationIsInvalid() {
        when(currencySyncClient.pullCurrencies()).thenReturn(List.of(
            currency("USDT", "TRC20", "TOldContract", 6)));
        currencySyncService.syncCurrencies();
        TronCurrencyConfig oldCurrency = currencyIndex.findByContractAddress("TOldContract");

        when(currencySyncClient.pullCurrencies()).thenReturn(List.of(
            currency("USDT", "TRC20", "", 6)));

        assertThatThrownBy(currencySyncService::syncCurrencies)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.CURRENCY_CONFIG_INVALID);
        assertThat(currencyIndex.size()).isEqualTo(1);
        assertThat(currencyIndex.findByContractAddress("TOldContract")).isEqualTo(oldCurrency);
        assertThat(currencyIndex.findByContractAddress("TNewContract")).isNull();
    }

    private ScannerCurrencyResp currency(String currency,
                                         String tokenStandard,
                                         String contractAddress,
                                         int decimals) {
        return new ScannerCurrencyResp()
            .setCurrency(currency)
            .setTokenStandard(tokenStandard)
            .setContractAddress(contractAddress)
            .setDecimals(decimals);
    }
}
