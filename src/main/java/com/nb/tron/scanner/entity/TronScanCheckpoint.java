package com.nb.tron.scanner.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.Instant;

/**
 * <p>
 * TRON Head 扫块检查点。
 * 保存每个 TRON 网络最后完整处理的 Head 高度和 Hash。
 * </p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Data
@Accessors(chain = true)
public class TronScanCheckpoint {

    /**
     * TRON 网络，例如 MAINNET、NILE
     */
    @TableId(value = "chain_network", type = IdType.INPUT)
    private String chainNetwork;

    /**
     * 最后完整解析并成功上报的 Head 区块高度
     */
    private Long lastBlockNumber;

    /**
     * 最后完整处理的 Head 区块 Hash
     */
    private String lastBlockHash;

    /**
     * 最近一次扫描进度变更时间
     */
    private Instant updatedAt;
}
