package com.nb.tron.scanner.biz;

import com.nb.chain.client.resp.ScannerCurrencyResp;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.client.CurrencySyncClient;
import com.nb.tron.scanner.enums.TronTokenStandard;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import com.nb.tron.scanner.support.TronAddressCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

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

    private static final String USDT_CONTRACT = "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

    private CurrencySyncClient currencySyncClient;

    private TronCurrencyIndex currencyIndex;

    private CurrencySyncService currencySyncService;

    @BeforeEach
    void setUp() {
        currencySyncClient = mock(CurrencySyncClient.class);
        currencyIndex = new TronCurrencyIndex();
        currencySyncService = new CurrencySyncService(currencySyncClient, currencyIndex, new TronAddressCodec());
    }

    @Test
    void shouldSyncCompleteCurrencySnapshot() {
        when(currencySyncClient.pullCurrencies()).thenReturn(List.of(
            currency("TRX", "NATIVE", "", 6),
            currency("USDT", "TRC20", USDT_CONTRACT, 6)));

        int currencyCount = currencySyncService.syncCurrencies();

        assertThat(currencyCount).isEqualTo(2);
        assertThat(currencyIndex.findNativeCurrency().tokenStandard())
            .isEqualTo(TronTokenStandard.NATIVE);
        assertThat(currencyIndex.findByContractAddress(USDT_CONTRACT).currency())
            .isEqualTo("USDT");
        assertThat(currencyIndex.isReady()).isTrue();
    }

    @Test
    void shouldKeepOldSnapshotWhenNewConfigurationIsInvalid() {
        when(currencySyncClient.pullCurrencies()).thenReturn(List.of(
            currency("USDT", "TRC20", USDT_CONTRACT, 6)));
        currencySyncService.syncCurrencies();
        TronCurrencyConfig oldCurrency = currencyIndex.findByContractAddress(USDT_CONTRACT);

        when(currencySyncClient.pullCurrencies()).thenReturn(List.of(
            currency("TRX", "NATIVE", "", 6),
            currency("USDT", "TRC20", "41a614f803b6fd780986a42c78ec9c7f77e6ded13c", 6)));

        assertThatThrownBy(currencySyncService::syncCurrencies)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.CURRENCY_CONFIG_INVALID);
        assertThat(currencyIndex.size()).isEqualTo(1);
        assertThat(currencyIndex.findByContractAddress(USDT_CONTRACT)).isEqualTo(oldCurrency);
        assertThat(currencyIndex.findNativeCurrency()).isNull();
        assertThat(currencyIndex.isReady()).isTrue();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
        " ",
        "a614f803b6fd780986a42c78ec9c7f77e6ded13c",
        "41a614f803b6fd780986a42c78ec9c7f77e6ded13c",
        "0x41a614f803b6fd780986a42c78ec9c7f77e6ded13c",
        "TUsdtContract",
        "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6x"
    })
    void shouldRejectInvalidContractBeforeIndexBecomesReady(String contractAddress) {
        when(currencySyncClient.pullCurrencies()).thenReturn(List.of(
            currency("USDT", "TRC20", contractAddress, 6)));

        assertThatThrownBy(currencySyncService::syncCurrencies)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.CURRENCY_CONFIG_INVALID);
        assertThat(currencyIndex.isReady()).isFalse();
        assertThat(currencyIndex.size()).isZero();
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
