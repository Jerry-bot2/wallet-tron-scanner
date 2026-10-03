package com.nb.tron.scanner.model;

/**
 * TRON 交易执行回执和事件日志
 *
 * @param transactionId 链上交易 ID
 * @param blockHeight   所属区块高度
 * @param rawJson       完整回执 JSON，包含执行结果和事件日志
 *                      Author: bin jack
 *                      Date: 03.10.26
 */
public record TronTransactionReceipt(String transactionId, long blockHeight, String rawJson) {
}
