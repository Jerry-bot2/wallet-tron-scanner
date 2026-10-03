package com.nb.tron.scanner.constant;

/**
 * TRON HTTP 协议常量。
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
public final class TronHttpConstants {

    public static final String API_KEY_HEADER = "TRON-PRO-API-KEY";

    public static final String EMPTY_REQUEST = "{}";

    public static final String FULL_NOW_BLOCK_PATH = "/wallet/getnowblock";

    public static final String FULL_BLOCK_BY_NUM_PATH = "/wallet/getblockbynum";

    public static final String SOLID_NOW_BLOCK_PATH = "/walletsolidity/getnowblock";

    public static final String SOLID_BLOCK_BY_NUM_PATH = "/walletsolidity/getblockbynum";

    public static final String TRANSACTION_INFO_BY_BLOCK_PATH = "/wallet/gettransactioninfobyblocknum";

    private TronHttpConstants() {
    }
}
