package com.nb.tron.scanner.service;

import com.nb.tron.scanner.entity.TronScannedBlock;

/**
 * 区块摘要数据库能力。联合主键不提供仅按单个 ID 操作的方法。
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
public interface ITronScannedBlockService {

    TronScannedBlock findByHeight(String chainNetwork, long blockNumber);

    /**
     * 查询最早保留的区块摘要，作为分叉查找的起点。
     */
    TronScannedBlock findOldestBlock(String chainNetwork);

    void saveBlock(TronScannedBlock block);

    /**
     * 删除指定高度之前的摘要，保留边界及之后的记录。
     *
     * @param chainNetwork 当前网络
     * @param blockNumber 清理边界，删除此高度之前符合条件的记录
     */
    void removeBefore(String chainNetwork, long blockNumber);

    /**
     * 删除共同区块之后的旧分支摘要，保留共同区块自身。
     */
    void removeAfter(String chainNetwork, long blockNumber);
}
