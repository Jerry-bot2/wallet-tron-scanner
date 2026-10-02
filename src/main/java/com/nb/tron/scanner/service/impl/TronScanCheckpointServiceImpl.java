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
public class TronScanCheckpointServiceImpl extends ServiceImpl<TronScanCheckpointMapper, TronScanCheckpoint>
        implements ITronScanCheckpointService {

    @Override
    public TronScanCheckpoint findByNetwork(String chainNetwork) {
        return getById(chainNetwork);
    }

    @Override
    public TronScanCheckpoint initializeIfAbsent(TronScanCheckpoint checkpoint) {
        return saveOrGet(checkpoint, TronScanCheckpoint::getChainNetwork);
    }

    @Override
    public boolean advance(String chainNetwork,
                           long expectedBlockNumber,
                           long nextBlockNumber,
                           String nextBlockHash) {
        return lambdaUpdate()
                .eq(TronScanCheckpoint::getChainNetwork, chainNetwork)
                .eq(TronScanCheckpoint::getLastBlockNumber, expectedBlockNumber)
                .set(TronScanCheckpoint::getLastBlockNumber, nextBlockNumber)
                .set(TronScanCheckpoint::getLastBlockHash, nextBlockHash)
                .update();
    }
}
