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
     * 短事务内锁定网络检查点，串行提交进度、清理摘要和分叉回退。
     */
    TronScanCheckpoint lockByNetwork(String chainNetwork);

    /**
     * 仅当数据库高度和Hash都与当前任务读取的检查点一致时推进。
     *
     * @param chainNetwork TRON网络
     * @param expectedBlockNumber 当前任务读取到的区块高度
     * @param expectedBlockHash 当前任务读取到的区块Hash
     * @param nextBlockNumber 本次处理完成的区块高度
     * @param nextBlockHash 本次处理完成的区块Hash
     * @return 是否推进成功
     */
    boolean advance(String chainNetwork, long expectedBlockNumber, String expectedBlockHash, long nextBlockNumber, String nextBlockHash);

    /**
     * 发现 Head 分叉时，按原高度和Hash条件回退检查点。
     *
     * @param chainNetwork 当前TRON网络
     * @param expectedBlockNumber 回退前的检查点高度
     * @param expectedBlockHash 回退前的检查点Hash
     * @param rewindBlockNumber 已核验的最近共同区块高度
     * @param rewindBlockHash 回退区块Hash
     * @return 是否回退成功
     */
    boolean rewind(String chainNetwork, long expectedBlockNumber, String expectedBlockHash, long rewindBlockNumber, String rewindBlockHash);
}
