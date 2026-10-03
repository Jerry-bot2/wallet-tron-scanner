package com.nb.tron.scanner.model;

import com.nb.tron.scanner.enums.TronTokenStandard;

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
}
