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
    public TronScanCheckpoint lockByNetwork(String chainNetwork) {
        return baseMapper.selectForUpdate(chainNetwork);
    }

    @Override
    public boolean advance(String chainNetwork, long expectedBlockNumber, String expectedBlockHash, long nextBlockNumber, String nextBlockHash) {
        return lambdaUpdate()
                .eq(TronScanCheckpoint::getChainNetwork, chainNetwork)
                .eq(TronScanCheckpoint::getLastBlockNumber, expectedBlockNumber)
                .eq(TronScanCheckpoint::getLastBlockHash, expectedBlockHash)
                .set(TronScanCheckpoint::getLastBlockNumber, nextBlockNumber)
                .set(TronScanCheckpoint::getLastBlockHash, nextBlockHash)
                .update();
    }

    @Override
    public boolean rewind(String chainNetwork, long expectedBlockNumber, String expectedBlockHash, long rewindBlockNumber, String rewindBlockHash) {
        return lambdaUpdate()
                .eq(TronScanCheckpoint::getChainNetwork, chainNetwork)
                .eq(TronScanCheckpoint::getLastBlockNumber, expectedBlockNumber)
                .eq(TronScanCheckpoint::getLastBlockHash, expectedBlockHash)
                .set(TronScanCheckpoint::getLastBlockNumber, rewindBlockNumber)
                .set(TronScanCheckpoint::getLastBlockHash, rewindBlockHash)
                .update();
    }
}
