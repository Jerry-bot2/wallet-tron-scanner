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

    /**
     * 查询不超过指定高度的最近一条摘要，兼容旧历史中每 1000 块保留一条的间隔。
     */
    TronScannedBlock findAtOrBefore(String chainNetwork, long blockNumber);

    /**
     * 查询指定高度之后的第一条摘要，不要求高度连续。
     */
    TronScannedBlock findNextBlock(String chainNetwork, long blockNumber);

    void saveBlock(TronScannedBlock block);

    /**
     * 全量重放时替换初始边界的 Hash，原 Hash 必须匹配，返回是否更新成功。
     */
    boolean replaceBoundaryHash(TronScannedBlock previous, String blockHash);

    /**
     * 按传入条件删除指定高度之前的非保留摘要。
     *
     * @param chainNetwork 当前网络
     * @param blockNumber 清理边界，删除此高度之前符合条件的记录
     * @param initialBlockNumber 始终保留的初始边界高度
     * @param anchorInterval 旧摘要保留间隔，整数倍高度保留
     */
    void compactBefore(String chainNetwork, long blockNumber, long initialBlockNumber, int anchorInterval);

    /**
     * 回退时删除共同区块之后的旧分支摘要。
     */
    void removeAfter(String chainNetwork, long blockNumber);
}
