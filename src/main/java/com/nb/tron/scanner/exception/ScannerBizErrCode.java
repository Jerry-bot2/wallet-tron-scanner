package com.nb.tron.scanner.exception;

import com.nb.core.exception.IBizErrCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TRON 扫描器错误码。
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Getter
@RequiredArgsConstructor
public enum ScannerBizErrCode implements IBizErrCode {

    SCANNER_RUNTIME_CONFIG_INVALID(910001, "扫描器运行配置不合法"),
    ADDRESS_INDEX_NOT_READY(911001, "地址内存索引尚未就绪"),
    ADDRESS_INDEX_DATA_INVALID(911002, "地址内存索引数据不合法"),
    ADDRESS_INDEX_DUPLICATE(911003, "地址内存索引存在重复地址"),
    ADDRESS_INDEX_CONFLICT(911004, "地址内存索引与同步数据不一致"),
    ADDRESS_SYNC_REMOTE_CALL_FAILED(912001, "查询链服务监控地址失败"),
    ADDRESS_SYNC_PAGE_INVALID(912002, "链服务监控地址分页数据不合法"),
    ADDRESS_SYNC_DATA_CONFLICT(912003, "地址同步数据与本地记录不一致"),
    ADDRESS_SYNC_SAVE_FAILED(912004, "地址同步数据保存失败"),
    ADDRESS_SYNC_ACK_FAILED(912005, "确认链服务地址监控水位失败"),
    TRON_NODE_CONFIG_INVALID(913001, "TRON 节点配置不合法"),
    ;

    private final Integer code;
    private final String desc;
}
