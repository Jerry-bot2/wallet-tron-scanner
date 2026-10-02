package com.nb.tron.scanner.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.nb.tron.scanner.enums.AddressPurpose;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * <p>
 * TRON 监控地址本地索引。
 * 保存从链服务同步并需要永久监控的平台地址。
 * </p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Data
@Accessors(chain = true)
public class TronMonitorAddress {

    /**
     * 链服务 chain_address.id，同时作为地址增量同步游标
     */
    @TableId(value = "source_address_id", type = IdType.INPUT)
    private Long sourceAddressId;

    /**
     * TRON 网络，例如 MAINNET、NILE
     */
    private String chainNetwork;

    /**
     * Base58Check 格式的 TRON 地址
     */
    private String address;

    /**
     * 地址用途，详见 {@link AddressPurpose}
     */
    private String addressPurpose;

    /**
     * 地址同步到扫描器的时间
     */
    private Instant createdAt;
}
