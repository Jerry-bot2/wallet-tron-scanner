package com.nb.tron.scanner.biz;

import com.nb.chain.client.resp.ScannerCurrencyResp;
import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.support.TronSdkCalls;
import com.nb.tron.scanner.client.CurrencySyncClient;
import com.nb.tron.sdk.enums.TronTokenStandard;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import com.nb.tron.sdk.codec.TronAddressCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * TRON 币种配置同步服务
 *
 * <p>负责拉取、校验并原子应用链服务提供的完整币种配置。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CurrencySyncService {

    private final CurrencySyncClient currencySyncClient;

    private final TronCurrencyIndex currencyIndex;

    private final TronAddressCodec addressCodec;

    /**
     * 刷新 Scanner 使用的完整币种快照。
     *
     * <p>
     * 1.拉取完整配置；
     * 2.逐条校验并转换，TRC20 合约必须使用标准 Base58Check 地址；
     * 3.全部成功后原子替换内存快照。
     * 任一步失败都保留上一版快照。
     * </p>
     *
     * @return 当前快照中的币种数量
     */
    public int syncCurrencies() {
        List<TronCurrencyConfig> currencies = currencySyncClient.pullCurrencies().stream()
            .map(this::convertCurrencyConfig)
            .toList();
        currencyIndex.replaceAll(currencies);
        log.info("TRON币种配置同步完成，currencyCount={}", currencies.size());
        return currencies.size();
    }

    private TronCurrencyConfig convertCurrencyConfig(ScannerCurrencyResp source) {
        BizAssert.notNull(source, ScannerBizErrCode.CURRENCY_CONFIG_INVALID);
        TronTokenStandard tokenStandard = TronTokenStandard.fromCode(source.getTokenStandard());
        BizAssert.isTrue(isValidCurrency(source, tokenStandard), ScannerBizErrCode.CURRENCY_CONFIG_INVALID);

        return new TronCurrencyConfig(
            source.getCurrency(),
            tokenStandard,
            source.getContractAddress(),
            source.getDecimals());
    }

    private boolean isValidCurrency(ScannerCurrencyResp source,
                                    TronTokenStandard tokenStandard) {
        return StringUtils.hasText(source.getCurrency())
            && tokenStandard != null
            && source.getDecimals() != null
            && source.getDecimals() >= 0
            && isValidContract(source.getContractAddress(), tokenStandard);
    }

    private boolean isValidContract(String contractAddress,
                                    TronTokenStandard tokenStandard) {
        // 原生币使用空合约；TRC20 必须配置标准 Base58Check 地址，才能匹配解析后的合约地址。
        return tokenStandard == TronTokenStandard.NATIVE
            ? contractAddress != null && contractAddress.isEmpty()
            : TronSdkCalls.execute(() -> addressCodec.isValidBase58Check(contractAddress));
    }
}
