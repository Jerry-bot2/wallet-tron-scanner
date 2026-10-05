package com.nb.tron.scanner.exception;

import com.nb.core.exception.IBizErrCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TRON 扫描器错误码
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Getter
@RequiredArgsConstructor
public enum ScannerBizErrCode implements IBizErrCode {

    /// ////////////////////////////////////// Scanner 运行配置 /////////////////////////////////////////
    SCANNER_RUNTIME_CONFIG_INVALID(910001, "扫描器运行配置不合法"),

    /// ////////////////////////////////////// 地址内存索引 /////////////////////////////////////////
    ADDRESS_INDEX_NOT_READY(911001, "地址内存索引尚未就绪"),
    ADDRESS_INDEX_DATA_INVALID(911002, "地址内存索引数据不合法"),
    ADDRESS_INDEX_DUPLICATE(911003, "地址内存索引存在重复地址"),
    ADDRESS_INDEX_CONFLICT(911004, "地址内存索引与同步数据不一致"),

    /// ////////////////////////////////////// 地址同步 /////////////////////////////////////////
    ADDRESS_SYNC_REMOTE_CALL_FAILED(912001, "查询链服务监控地址失败"),
    ADDRESS_SYNC_PAGE_INVALID(912002, "链服务监控地址分页数据不合法"),
    ADDRESS_SYNC_DATA_CONFLICT(912003, "地址同步数据与本地记录不一致"),
    ADDRESS_SYNC_SAVE_FAILED(912004, "地址同步数据保存失败"),
    ADDRESS_SYNC_ACK_FAILED(912005, "确认链服务地址监控水位失败"),

    /// ////////////////////////////////////// TRON 节点 /////////////////////////////////////////
    TRON_NODE_CONFIG_INVALID(913001, "TRON 节点配置不合法"),
    TRON_NODE_CONNECT_FAILED(913002, "TRON 节点连接失败"),
    TRON_NODE_TIMEOUT(913003, "TRON 节点请求超时"),
    TRON_NODE_RATE_LIMITED(913004, "TRON 节点请求被限流"),
    TRON_NODE_REMOTE_ERROR(913005, "TRON 节点返回错误"),
    TRON_NODE_RESPONSE_INVALID(913006, "TRON 节点响应不合法"),
    TRON_NODE_NETWORK_MISMATCH(913007, "TRON 节点网络不匹配"),
    TRON_BLOCK_NOT_FOUND(913008, "TRON 区块不存在或节点尚未同步"),
    TRON_NODE_UNAVAILABLE(913009, "没有可用的 TRON 节点"),

    /// ////////////////////////////////////// 币种配置 /////////////////////////////////////////
    CURRENCY_INDEX_NOT_READY(914001, "币种配置内存快照尚未就绪"),
    CURRENCY_SYNC_REMOTE_CALL_FAILED(914002, "查询链服务币种配置失败"),
    CURRENCY_CONFIG_INVALID(914003, "币种配置不合法"),
    CURRENCY_CONFIG_DUPLICATE(914004, "币种配置存在重复资产"),

    /// ////////////////////////////////////// TRON 交易解析 /////////////////////////////////////////
    TRON_ADDRESS_INVALID(915001, "TRON 地址格式不合法"),
    TRON_TRANSACTION_INVALID(915002, "TRON 交易数据不合法"),
    TRON_BLOCK_DATA_INCOMPLETE(915003, "TRON 区块交易或回执数据不完整"),

    /// ////////////////////////////////////// Head 区块扫描 /////////////////////////////////////////
    HEAD_SCAN_CHECKPOINT_CONFLICT(916001, "Head 扫块检查点已被其他任务更新"),
    HEAD_SCAN_KAFKA_PUBLISH_FAILED(916002, "Head 扫块充值事件投递未确认，区块高度 {0}"),
    HEAD_SCAN_FORK_DETECTED(916003, "检测到 Head 分叉，当前区块高度 {0}，检查点已回退至 {1}"),

    HEAD_SCAN_HISTORY_INVALID(916004, "区块摘要缺失或与检查点不一致，请检查数据或完成旧版本初始化回放"),
    HEAD_SCAN_COMMON_ANCESTOR_NOT_FOUND(916005, "保留范围内找不到共同区块，停止扫描，请检查节点或扩展历史回放"),
    HEAD_SCAN_CHAIN_CHANGED(916006, "查找共同区块期间链发生变化，本轮结束，下轮重新查找"),

    /// ////////////////////////////////////// Scanner 内部错误 /////////////////////////////////////////
    CRYPTO_ALGORITHM_UNAVAILABLE(919001, "系统加密算法不可用"),
    ;

    private final Integer code;
    private final String desc;
}
