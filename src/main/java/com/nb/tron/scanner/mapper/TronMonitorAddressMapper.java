package com.nb.tron.scanner.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nb.tron.scanner.entity.TronMonitorAddress;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
@Mapper
public interface TronMonitorAddressMapper extends BaseMapper<TronMonitorAddress> {

    /**
     * 查询指定网络已经保存的最大源地址ID。
     *
     * @param chainNetwork TRON网络
     * @return 最大源地址ID，无数据时返回0
     */
    @Select("""
            SELECT COALESCE(MAX(source_address_id), 0)
            FROM tron_monitor_address
            WHERE chain_network = #{chainNetwork}
            """)
    long selectMaxSourceAddressId(@Param("chainNetwork") String chainNetwork);
}
