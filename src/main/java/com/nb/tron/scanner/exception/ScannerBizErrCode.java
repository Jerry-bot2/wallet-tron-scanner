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
    ;

    private final Integer code;
    private final String desc;
}
