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
     * 一条最终确认消息最多携带300笔充值，超出时按固定数量拆分。
     */
    public static final int DEPOSIT_MESSAGE_BATCH_SIZE = 300;

    private TronConstants() {
    }
}
