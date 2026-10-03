package com.nb.tron.scanner.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 完整区块数据
 *
 * @param nodeCode       实际响应的节点编码
 * @param blockHeight    区块高度
 * @param blockId        当前区块 ID
 * @param parentBlockId  父区块 ID
 * @param blockTimestamp 链上区块时间
 * @param transactions   区块交易列表
 * @param receipts       按交易 ID 索引的执行回执
 *                       Author: bin jack
 *                       Date: 03.10.26
 */
public record TronBlockData(String nodeCode,
                            long blockHeight,
                            String blockId,
                            String parentBlockId,
                            Instant blockTimestamp,
                            List<TronTransaction> transactions,
                            Map<String, TronTransactionReceipt> receipts) {

    public TronBlockData {
        transactions = List.copyOf(transactions);
        receipts = Map.copyOf(receipts);
    }
}
