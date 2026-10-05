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
     * 查询最早保留的区块摘要，仅代表共同区块查找的下界。
     */
    TronScannedBlock findOldestBlock(String chainNetwork);

    void saveBlock(TronScannedBlock block);

    /**
     * 删除指定高度之前的摘要。
     */
    void removeBefore(String chainNetwork, long blockNumber);

    /**
     * 回退时删除共同区块之后的旧分支摘要。
     */
    void removeAfter(String chainNetwork, long blockNumber);
}
