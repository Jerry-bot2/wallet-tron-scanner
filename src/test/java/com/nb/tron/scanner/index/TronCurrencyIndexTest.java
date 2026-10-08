package com.nb.tron.scanner.index;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import com.nb.tron.sdk.codec.TronAddressCodec;
import com.nb.tron.sdk.enums.TronTokenStandard;
import com.nb.tron.sdk.model.TronAsset;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class TronCurrencyIndexTest {

    private static final TronAddressCodec ADDRESS_CODEC = new TronAddressCodec();
    private static final String USDT_CONTRACT = ADDRESS_CODEC.fromHex("11".repeat(20));
    private static final String OLD_CONTRACT = ADDRESS_CODEC.fromHex("22".repeat(20));
    private static final String NEW_CONTRACT = ADDRESS_CODEC.fromHex("33".repeat(20));
    private static final String UNKNOWN_CONTRACT = ADDRESS_CODEC.fromHex("44".repeat(20));

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
        TronCurrencyConfig usdt = currency("USDT", TronTokenStandard.TRC20, USDT_CONTRACT, 6);

        currencyIndex.replaceAll(List.of(trx, usdt));

        assertThat(currencyIndex.isReady()).isTrue();
        assertThat(currencyIndex.size()).isEqualTo(2);
        assertThat(currencyIndex.findNativeCurrency()).isEqualTo(trx);
        assertThat(currencyIndex.findByContractAddress(USDT_CONTRACT)).isEqualTo(usdt);
        assertThat(currencyIndex.findByContractAddress(UNKNOWN_CONTRACT)).isNull();
    }

    @Test
    void shouldKeepOldSnapshotWhenNewSnapshotContainsDuplicateContract() {
        TronCurrencyIndex currencyIndex = new TronCurrencyIndex();
        TronCurrencyConfig oldUsdt = currency("USDT", TronTokenStandard.TRC20, OLD_CONTRACT, 6);
        currencyIndex.replaceAll(List.of(oldUsdt));

        assertThatThrownBy(() -> currencyIndex.replaceAll(List.of(
            currency("USDT", TronTokenStandard.TRC20, NEW_CONTRACT, 6),
            currency("USDC", TronTokenStandard.TRC20, NEW_CONTRACT, 6))))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.CURRENCY_CONFIG_DUPLICATE);

        assertThat(currencyIndex.size()).isEqualTo(1);
        assertThat(currencyIndex.findByContractAddress(OLD_CONTRACT)).isEqualTo(oldUsdt);
        assertThat(currencyIndex.findByContractAddress(NEW_CONTRACT)).isNull();
    }

    @Test
    void shouldKeepRoundSnapshotUnchangedWhenCurrencyConfigurationIsRefreshed() {
        TronCurrencyIndex currencyIndex = new TronCurrencyIndex();
        TronCurrencyConfig oldUsdt = currency("USDT", TronTokenStandard.TRC20, OLD_CONTRACT, 6);
        currencyIndex.replaceAll(List.of(oldUsdt));
        Map<TronAsset, TronCurrencyConfig> roundSnapshot = currencyIndex.snapshot();

        currencyIndex.replaceAll(List.of(currency("USDT", TronTokenStandard.TRC20, NEW_CONTRACT, 6)));

        assertThat(roundSnapshot).containsOnlyKeys(TronAsset.trc20(OLD_CONTRACT));
        assertThat(roundSnapshot.get(TronAsset.trc20(OLD_CONTRACT))).isEqualTo(oldUsdt);
        assertThat(currencyIndex.snapshot()).containsOnlyKeys(TronAsset.trc20(NEW_CONTRACT));
        assertThatThrownBy(roundSnapshot::clear).isInstanceOf(UnsupportedOperationException.class);
    }

    private TronCurrencyConfig currency(String currency,
                                        TronTokenStandard tokenStandard,
                                        String contractAddress,
                                        int decimals) {
        return new TronCurrencyConfig(currency, tokenStandard, contractAddress, decimals);
    }
}
