package com.nb.tron.scanner.constant;

/**
 * TRON 扫描器常量
 * <p>
 * Author: bin jack
 * Date: 03.10.26
    */
public final class TronConstants {

    /**
     * TRON 链编码
     */
    public static final String CHAIN_CODE = "TRON";

    /**
     * TRON 创世区块高度。
     */
    public static final long GENESIS_BLOCK_HEIGHT = 0L;

    /**
     * 每扫描到 100 的整数倍高度，清理一次旧摘要，避免每块都查询和清理历史。
     */
    public static final int BLOCK_HISTORY_CLEANUP_INTERVAL = 100;

    /**
     * 一条充值发现消息最多携带 300 笔充值，超出时按固定数量拆分。
     */
    public static final int DEPOSIT_MESSAGE_BATCH_SIZE = 300;

    private TronConstants() {
    }
}
