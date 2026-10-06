package com.nb.tron.scanner.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.mapper.TronScanCheckpointMapper;
import com.nb.tron.scanner.service.ITronScanCheckpointService;
import org.springframework.stereotype.Service;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
@Service
public class TronScanCheckpointServiceImpl extends ServiceImpl<TronScanCheckpointMapper, TronScanCheckpoint> implements ITronScanCheckpointService {

    @Override
    public TronScanCheckpoint findByNetwork(String chainNetwork) {
        return getById(chainNetwork);
    }

    @Override
    public boolean updatePosition(String chainNetwork, long blockNumber, String blockHash) {
        return lambdaUpdate()
                .eq(TronScanCheckpoint::getChainNetwork, chainNetwork)
                .set(TronScanCheckpoint::getLastBlockNumber, blockNumber)
                .set(TronScanCheckpoint::getLastBlockHash, blockHash)
                .update();
    }
}
