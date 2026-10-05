package com.nb.tron.scanner.service;

import com.nb.mybatis.service.IBaseService;
import com.nb.tron.scanner.entity.TronScanCheckpoint;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
public interface ITronScanCheckpointService extends IBaseService<TronScanCheckpoint> {

    /**
     * 查询指定网络的Head扫块检查点。
     *
     * @param chainNetwork TRON网络
     * @return 检查点，不存在时返回null
     */
    TronScanCheckpoint findByNetwork(String chainNetwork);

    /**
     * 初始化指定网络的检查点，并发重复初始化时返回数据库已有记录。
     *
     * @param checkpoint 初始检查点
     * @return 新增或已经存在的检查点
     */
    TronScanCheckpoint initializeIfAbsent(TronScanCheckpoint checkpoint);

    /**
     * 仅当数据库仍处于预期高度时推进Head扫块检查点。
     *
     * @param chainNetwork TRON网络
     * @param expectedBlockNumber 当前任务读取到的区块高度
     * @param nextBlockNumber 本次处理完成的区块高度
     * @param nextBlockHash 本次处理完成的区块Hash
     * @return 是否推进成功
     */
    boolean advance(String chainNetwork, long expectedBlockNumber, long nextBlockNumber, String nextBlockHash);
}
