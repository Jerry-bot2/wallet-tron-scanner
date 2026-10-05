package com.nb.tron.scanner.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
@Mapper
public interface TronScanCheckpointMapper extends BaseMapper<TronScanCheckpoint> {

    @Select("""
        SELECT chain_network, last_block_number, last_block_hash, updated_at
        FROM tron_scan_checkpoint WHERE chain_network = #{chainNetwork} FOR UPDATE
        """)
    TronScanCheckpoint selectForUpdate(@Param("chainNetwork") String chainNetwork);
}
