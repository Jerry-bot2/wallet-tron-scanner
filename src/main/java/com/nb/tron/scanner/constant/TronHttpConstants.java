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
     * 查询最新区块头；省略高度表示最新区块，detail=false 表示不返回交易列表。
     */
    public static final String LATEST_BLOCK_HEADER_REQUEST = "{\"detail\":false}";

    /**
     * 查询 FullNode 区块头；detail=false 只返回区块头，省略 id_or_num 时查询最新 Head。
     */
    public static final String FULL_BLOCK_HEADER_PATH = "/wallet/getblock";

    /**
     * 按区块高度查询 FullNode 中的完整区块和交易列表。
     */
    public static final String FULL_BLOCK_BY_NUM_PATH = "/wallet/getblockbynum";

    /**
     * 查询 SolidityNode 固化区块头；detail=false 不返回交易，省略高度时查询最新固化块。
     */
    public static final String SOLID_BLOCK_HEADER_PATH = "/walletsolidity/getblock";

    /**
     * 按区块高度批量查询交易执行结果、资源消耗和合约事件日志。
     */
    public static final String TRANSACTION_INFO_BY_BLOCK_PATH = "/wallet/gettransactioninfobyblocknum";

    private TronHttpConstants() {
    }
}
