package com.nb.tron.scanner.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.mapper.TronScannedBlockMapper;
import com.nb.tron.scanner.service.ITronScannedBlockService;
import org.springframework.stereotype.Service;

/**
 * Author: bin jack
 * Date: 05.10.26
 */
@Service
public class TronScannedBlockServiceImpl extends ServiceImpl<TronScannedBlockMapper, TronScannedBlock> implements ITronScannedBlockService {

    @Override
    public TronScannedBlock findByHeight(String chainNetwork, long blockNumber) {
        return lambdaQuery()
                .eq(TronScannedBlock::getChainNetwork, chainNetwork)
                .eq(TronScannedBlock::getBlockNumber, blockNumber)
                .one();
    }

    @Override
    public TronScannedBlock findOldestBlock(String chainNetwork) {
        return lambdaQuery()
                .eq(TronScannedBlock::getChainNetwork, chainNetwork)
                .orderByAsc(TronScannedBlock::getBlockNumber)
                .last("LIMIT 1")
                .one();
    }

    @Override
    public void saveBlock(TronScannedBlock block) {
        save(block);
    }

    @Override
    public void removeBefore(String chainNetwork, long blockNumber) {
        lambdaUpdate()
                .eq(TronScannedBlock::getChainNetwork, chainNetwork)
                .lt(TronScannedBlock::getBlockNumber, blockNumber)
                .remove();
    }

    @Override
    public void removeAfter(String chainNetwork, long blockNumber) {
        lambdaUpdate()
                .eq(TronScannedBlock::getChainNetwork, chainNetwork)
                .gt(TronScannedBlock::getBlockNumber, blockNumber)
                .remove();
    }
}
