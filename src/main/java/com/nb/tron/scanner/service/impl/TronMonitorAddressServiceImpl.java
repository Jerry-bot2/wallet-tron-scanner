package com.nb.tron.scanner.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.nb.tron.scanner.entity.TronMonitorAddress;
import com.nb.tron.scanner.mapper.TronMonitorAddressMapper;
import com.nb.tron.scanner.service.ITronMonitorAddressService;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
@Service
public class TronMonitorAddressServiceImpl extends ServiceImpl<TronMonitorAddressMapper, TronMonitorAddress>
        implements ITronMonitorAddressService {

    @Override
    public List<TronMonitorAddress> listByNetwork(String chainNetwork) {
        return lambdaQuery()
                .eq(TronMonitorAddress::getChainNetwork, chainNetwork)
                .orderByAsc(TronMonitorAddress::getSourceAddressId)
                .list();
    }

    @Override
    public long findMaxSourceAddressId(String chainNetwork) {
        return baseMapper.selectMaxSourceAddressId(chainNetwork);
    }
}
