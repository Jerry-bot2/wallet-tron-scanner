package com.nb.tron.scanner.model;

import java.time.Instant;

/**
 * 节点返回的区块高度信息
 *
 * @param nodeCode       实际响应的节点编码
 * @param blockHeight    区块高度
 * @param blockId        区块 ID
 * @param blockTimestamp 链上区块时间
 *                       Author: bin jack
 *                       Date: 03.10.26
 */
public record TronNodeHeight(String nodeCode,
                             long blockHeight,
                             String blockId,
                             Instant blockTimestamp) {
}
