package com.nb.tron.scanner.support;

import com.nb.core.exception.BizException;
import com.nb.core.exception.SystemException;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.sdk.exception.TronSdkException;

import java.util.function.Supplier;

/**
 * Scanner SDK调用边界，协议异常转换为现有业务错误码
 * <p>
 * Author: bin jack
 * Date: 07.10.26
 */
public final class TronSdkCalls {

    /** SDK协议异常转换成当前服务错误码，保留原因以便排查；不重试、不输出重复日志。 */
    public static <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (TronSdkException exception) {
            ScannerBizErrCode code = switch (exception.getError()) {
                case CONFIG_INVALID -> ScannerBizErrCode.TRON_NODE_CONFIG_INVALID;
                case CONNECT_FAILED -> ScannerBizErrCode.TRON_NODE_CONNECT_FAILED;
                case TIMEOUT -> ScannerBizErrCode.TRON_NODE_TIMEOUT;
                case RATE_LIMITED -> ScannerBizErrCode.TRON_NODE_RATE_LIMITED;
                case REMOTE_ERROR -> ScannerBizErrCode.TRON_NODE_REMOTE_ERROR;
                case INVALID_RESPONSE -> ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID;
                case BLOCK_NOT_FOUND -> ScannerBizErrCode.TRON_BLOCK_NOT_FOUND;
                case INVALID_ADDRESS -> ScannerBizErrCode.TRON_ADDRESS_INVALID;
                case INCOMPLETE_BLOCK -> ScannerBizErrCode.TRON_BLOCK_DATA_INCOMPLETE;
                case INVALID_TRANSACTION -> ScannerBizErrCode.TRON_TRANSACTION_INVALID;
                case CRYPTO_UNAVAILABLE -> throw new SystemException(ScannerBizErrCode.CRYPTO_ALGORITHM_UNAVAILABLE, exception);
            };
            throw new BizException(code, exception);
        }
    }

    private TronSdkCalls() {
    }
}
