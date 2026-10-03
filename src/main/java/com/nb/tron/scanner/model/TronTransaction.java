package com.nb.tron.scanner.model;

/**
 * TRON 区块中的交易
 *
 * @param transactionId 链上交易 ID
 * @param rawJson       完整交易 JSON，供后续交易解析使用
 *                      Author: bin jack
 *                      Date: 03.10.26
 */
public record TronTransaction(String transactionId,
                              String rawJson) {
}
