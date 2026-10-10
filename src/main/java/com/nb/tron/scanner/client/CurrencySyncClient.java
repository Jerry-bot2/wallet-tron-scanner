package com.nb.tron.scanner.client;

import com.nb.chain.client.api.ChainScannerClient;
import com.nb.chain.client.resp.ScannerCurrencyResp;
import com.nb.core.exception.BizAssert;
import com.nb.core.response.Result;
import com.nb.tron.scanner.constant.TronConstants;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 链服务币种配置同步客户端。
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
@RequiredArgsConstructor
public class CurrencySyncClient {

    private final ChainScannerClient chainScannerClient;

    /**
     * 拉取当前运行网络需要识别的完整币种配置。
     */
    public List<ScannerCurrencyResp> pullCurrencies() {
        Result<List<ScannerCurrencyResp>> result = chainScannerClient.listCurrencies(TronConstants.CHAIN_CODE);
        BizAssert.isTrue(result != null && result.successful(), ScannerBizErrCode.CURRENCY_SYNC_REMOTE_CALL_FAILED);
        BizAssert.notEmpty(result.getData(), ScannerBizErrCode.CURRENCY_CONFIG_INVALID);
        return result.getData();
    }
}
