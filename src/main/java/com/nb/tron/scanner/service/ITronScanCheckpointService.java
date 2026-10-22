package com.nb.tron.scanner.service;

import com.nb.mybatis.service.IBaseService;
import com.nb.tron.scanner.entity.TronScanCheckpoint;

/**
 * 扫描检查点的数据库操作。
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
public interface ITronScanCheckpointService extends IBaseService<TronScanCheckpoint> {

    /**
     * 查询指定网络的扫描进度，不存在时返回 null。
     */
    TronScanCheckpoint findByNetwork(String chainNetwork);

    /**
     * 更新最后完整处理的固化区块高度和Hash。
     */
    boolean updatePosition(String chainNetwork, long blockNumber, String blockHash);
}
