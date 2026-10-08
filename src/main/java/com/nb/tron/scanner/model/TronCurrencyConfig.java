package com.nb.tron.scanner.model;

import com.nb.tron.sdk.enums.TronTokenStandard;
import com.nb.tron.sdk.model.TronAsset;

/**
 * Scanner 解析交易所需的 TRON 币种配置。
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 *
 * @param currency        币种编码，例如 TRX、USDT
 * @param tokenStandard   资产标准
 * @param contractAddress TRC20 合约地址；原生币为空字符串
 * @param decimals        链上精度
 */
public record TronCurrencyConfig(String currency,
                                 TronTokenStandard tokenStandard,
                                 String contractAddress,
                                 int decimals) {
    /**
     * 交给SDK的资产身份，业务币种和精度仍由Scanner管理。
     */
    public TronAsset asset() {
        return new TronAsset(tokenStandard, contractAddress);
    }
}
