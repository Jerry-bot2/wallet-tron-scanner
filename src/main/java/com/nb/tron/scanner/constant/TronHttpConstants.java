package com.nb.tron.scanner.constant;

/**
 * TRON HTTP 协议常量
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
public final class TronHttpConstants {

    /**
     * TronGrid API Key 请求头名称；调用自建 java-tron 节点时可以不携带。
     */
    public static final String API_KEY_HEADER = "TRON-PRO-API-KEY";

    /**
     * 无请求参数的 TRON HTTP 接口使用的空 JSON 请求体。
     */
    public static final String EMPTY_REQUEST = "{}";

    /**
     * 查询 FullNode 当前最新 Head 区块。
     */
    public static final String FULL_NOW_BLOCK_PATH = "/wallet/getnowblock";

    /**
     * 按区块高度查询 FullNode 中的完整区块和交易列表。
     */
    public static final String FULL_BLOCK_BY_NUM_PATH = "/wallet/getblockbynum";

    /**
     * 查询 SolidityNode 当前最新固化区块。
     */
    public static final String SOLID_NOW_BLOCK_PATH = "/walletsolidity/getnowblock";

    /**
     * 按区块高度查询 SolidityNode 中已经固化的区块。
     */
    public static final String SOLID_BLOCK_BY_NUM_PATH = "/walletsolidity/getblockbynum";

    /**
     * 按区块高度批量查询交易执行结果、资源消耗和合约事件日志。
     */
    public static final String TRANSACTION_INFO_BY_BLOCK_PATH = "/wallet/gettransactioninfobyblocknum";

    private TronHttpConstants() {
    }
}
