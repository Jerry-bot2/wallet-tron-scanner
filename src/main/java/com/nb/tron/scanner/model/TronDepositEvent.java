package com.nb.tron.scanner.model;

import java.math.BigInteger;
import java.time.Instant;

/**
 * TRON 扫描器识别出的充值事实
 *
 * <p>该模型只保存链上原始数据，不换算展示金额，也不表示充值已经固化到账</p>
 *
 * @param chainCode       链编码，固定为 TRON
 * @param chainNetwork    当前网络，例如 MAINNET、NILE
 * @param currency        币种编码，例如 TRX、USDT
 * @param contractAddress 代币合约地址；原生 TRX 为空字符串
 * @param txId            链上交易 ID
 * @param eventIndex      交易内事件序号；TRX 使用负数，TRC20 使用日志序号
 * @param blockNumber     发现交易时所在的区块高度
 * @param blockHash       发现交易时所在的区块 ID
 * @param blockTimestamp  链上区块时间
 * @param fromAddress     付款地址，统一为 TRON Base58Check 地址
 * @param toAddress       平台收款地址，统一为 TRON Base58Check 地址
 * @param rawAmount       链上最小单位整数金额，不做精度换算
 *                        Author: bin jack
 *                        Date: 03.10.26
 */
public record TronDepositEvent(String chainCode,
                               String chainNetwork,
                               String currency,
                               String contractAddress,
                               String txId,
                               int eventIndex,
                               long blockNumber,
                               String blockHash,
                               Instant blockTimestamp,
                               String fromAddress,
                               String toAddress,
                               BigInteger rawAmount) {
}
