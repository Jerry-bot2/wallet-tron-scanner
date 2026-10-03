package com.nb.tron.scanner.constant;

/**
 * TRON 交易协议常量
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
public final class TronTransactionConstants {

    /**
     * TRX 原生转账合约类型
     */
    public static final String TRANSFER_CONTRACT_TYPE = "TransferContract";

    /**
     * TRON 交易执行成功状态
     */
    public static final String CONTRACT_SUCCESS = "SUCCESS";

    /**
     * TRC20 标准 Transfer 事件签名
     */
    public static final String TRC20_TRANSFER_EVENT_TOPIC =
        "ddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";

    /**
     * TRC20 Transfer 事件固定包含事件签名、付款地址和收款地址三个 Topic
     */
    public static final int TRC20_TRANSFER_TOPIC_COUNT = 3;

    /**
     * TRC20 Transfer 金额为 32 字节无符号整数
     */
    public static final int TRC20_TRANSFER_AMOUNT_BYTES = 32;

    /**
     * TRON 交易回执明确失败状态
     */
    public static final String TRANSACTION_RECEIPT_FAILED = "FAILED";

    private TronTransactionConstants() {
    }
}
