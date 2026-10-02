package com.nb.tron.scanner.service;

import com.nb.mybatis.service.IBaseService;
import com.nb.tron.scanner.entity.TronMonitorAddress;

import java.util.Collection;
import java.util.List;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
public interface ITronMonitorAddressService extends IBaseService<TronMonitorAddress> {

    /**
     * 按源地址ID升序加载指定网络的全部监控地址。
     *
     * @param chainNetwork TRON网络
     * @return 监控地址列表
     */
    List<TronMonitorAddress> listByNetwork(String chainNetwork);

    /**
     * 查询指定网络已经保存的最大源地址ID。
     *
     * @param chainNetwork TRON网络
     * @return 最大源地址ID，无数据时返回0
     */
    long findMaxSourceAddressId(String chainNetwork);

    /**
     * 按源地址ID或当前网络地址查询已经存在的监控地址。
     *
     * @param chainNetwork TRON网络
     * @param sourceAddressIds 链服务地址ID集合
     * @param addresses TRON地址集合
     * @return 已存在的监控地址
     */
    List<TronMonitorAddress> listBySourceIdsOrAddresses(String chainNetwork, Collection<Long> sourceAddressIds, Collection<String> addresses);

}
