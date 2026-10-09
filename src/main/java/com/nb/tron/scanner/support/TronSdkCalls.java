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

    /**
     * SDK协议异常转换成当前服务错误码，保留原因以便排查；不重试、不输出重复日志。
     */
    public static <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (TronSdkException exception) {
            throw toServiceException(exception);
        }
    }

    private static RuntimeException toServiceException(TronSdkException exception) {
        if (exception.isSystemFailure()) {
            return new SystemException(ScannerBizErrCode.CRYPTO_ALGORITHM_UNAVAILABLE, exception);
        }
        if (exception.isConfigurationFailure()) {
            return new BizException(ScannerBizErrCode.TRON_SDK_CONFIG_INVALID, exception);
        }
        if (exception.isDataFailure()) {
            return new BizException(ScannerBizErrCode.TRON_SDK_DATA_INVALID, exception);
        }
        return new BizException(ScannerBizErrCode.TRON_SDK_CALL_FAILED, exception);
    }

    private TronSdkCalls() {
    }
}
