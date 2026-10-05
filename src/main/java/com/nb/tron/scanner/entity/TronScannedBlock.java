package com.nb.tron.scanner.entity;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * TRON 已扫描区块摘要
 *
 * <p>
 * 每个网络保存最近一段已扫描区块的高度和 Hash，用于分叉时查找最近的共同区块。
 * 最小高度仅代表保留范围的下界，不代表固化或安全位置。
 * </p>
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
@Data
@Accessors(chain = true)
public class TronScannedBlock {

    /**
     * TRON 网络，与区块高度组成联合主键
     */
    private String chainNetwork;

    /**
     * 已处理区块高度；从创世块扫描时，初始标记使用 -1
     */
    private Long blockNumber;

    /**
     * 当时实际处理的区块 Hash；高度为 -1 时为空字符串
     */
    private String blockHash;
}
