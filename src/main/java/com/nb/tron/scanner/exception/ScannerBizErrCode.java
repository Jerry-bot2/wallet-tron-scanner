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

    /// ////////////////////////////////////// TRON SDK /////////////////////////////////////////
    TRON_SDK_CONFIG_INVALID(913001, "TRON SDK 配置不合法"),
    TRON_SDK_CALL_FAILED(913002, "TRON SDK 调用失败"),
    TRON_SDK_DATA_INVALID(913003, "TRON SDK 返回或解析的数据不合法"),

    /// ////////////////////////////////////// 币种配置 /////////////////////////////////////////
    CURRENCY_INDEX_NOT_READY(914001, "币种配置内存快照尚未就绪"),
    CURRENCY_SYNC_REMOTE_CALL_FAILED(914002, "查询链服务币种配置失败"),
    CURRENCY_CONFIG_INVALID(914003, "币种配置不合法"),
    CURRENCY_CONFIG_DUPLICATE(914004, "币种配置存在重复资产"),

    /// ////////////////////////////////////// Head 区块扫描 /////////////////////////////////////////
    HEAD_SCAN_CHECKPOINT_SAVE_FAILED(916001, "Head 扫块进度保存失败"),
    HEAD_SCAN_KAFKA_PUBLISH_FAILED(916002, "Head 扫块充值事件投递未确认，区块高度 {0}"),

    HEAD_SCAN_HISTORY_INVALID(916004, "区块摘要缺失或与检查点不一致"),
    HEAD_SCAN_CHAIN_CHANGED(916006, "查找共同区块期间链发生变化，本轮结束，下轮重新查找"),
    HEAD_SCAN_COMMON_ANCESTOR_NOT_FOUND(916008, "保留的区块摘要中未找到共同区块，扫描进度保持不变"),

    /// ////////////////////////////////////// Scanner 内部错误 /////////////////////////////////////////
    CRYPTO_ALGORITHM_UNAVAILABLE(919001, "系统加密算法不可用"),
    ;

    private final Integer code;
    private final String desc;
}
