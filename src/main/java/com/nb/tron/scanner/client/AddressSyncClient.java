package com.nb.tron.scanner.client;

import com.nb.chain.client.api.ChainScannerClient;
import com.nb.chain.client.req.ScannerAddressAckReq;
import com.nb.chain.client.resp.ScannerAddressPageResp;
import com.nb.chain.client.resp.ScannerAddressResp;
import com.nb.core.exception.BizAssert;
import com.nb.core.response.Result;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


/**
 * 链服务地址同步客户端。
 *
 * <p>按照地址游标获取增量地址，并确认已安全应用的监控水位。</p>
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Component
@RequiredArgsConstructor
public class AddressSyncClient {

    private final ChainScannerClient chainScannerClient;

    private final TronScannerProperties scannerProperties;

    /**
     * 查询指定地址ID之后的一页监控地址。
     *
     * @param afterAddressId 当前已经应用的最大地址ID
     * @return 校验通过的地址分页
     */
    public ScannerAddressPageResp pullNextPage(long afterAddressId) {
        Result<ScannerAddressPageResp> result = chainScannerClient.listAddresses(scannerProperties.getChainCode(), afterAddressId);
        BizAssert.isTrue(result != null && result.successful(), ScannerBizErrCode.ADDRESS_SYNC_REMOTE_CALL_FAILED);

        ScannerAddressPageResp addressPage = result.getData();
        validateCursor(addressPage, afterAddressId);
        return addressPage;
    }

    /**
     * 确认已持久化并加载到内存的地址水位。
     *
     * <p>ACK 只允许链服务水位向前推进，重复提交同一水位保持幂等。</p>
     */
    public void acknowledge(long appliedMaxAddressId) {
        ScannerAddressAckReq request = new ScannerAddressAckReq()
            .setChainCode(scannerProperties.getChainCode())
            .setAppliedMaxAddressId(appliedMaxAddressId);
        Result<Void> result = chainScannerClient.ackAddressWatermark(request);
        BizAssert.isTrue(result != null && result.successful(), ScannerBizErrCode.ADDRESS_SYNC_ACK_FAILED);
    }

    /**
     * 校验地址分页游标。
     *
     * <p>
     * 确保地址ID严格递增、分页最大ID与最后一条地址一致，并禁止空页继续翻页，
     * 避免同步游标倒退、跳过地址或重复拉取空页。
     * </p>
     */
    private void validateCursor(ScannerAddressPageResp addressPage, long afterAddressId) {
        BizAssert.notNull(addressPage, ScannerBizErrCode.ADDRESS_SYNC_PAGE_INVALID);

        long lastAddressId = afterAddressId;
        for (ScannerAddressResp address : addressPage.getAddresses()) {
            Long addressId = address.getAddressId();
            BizAssert.isTrue(addressId != null && addressId > lastAddressId, ScannerBizErrCode.ADDRESS_SYNC_PAGE_INVALID);
            lastAddressId = addressId;
        }

        boolean validCursor = addressPage.getMaxAddressId() != null
            && addressPage.getMaxAddressId() == lastAddressId
            && (!addressPage.getAddresses().isEmpty() || !Boolean.TRUE.equals(addressPage.getHasMore()));
        BizAssert.isTrue(validCursor, ScannerBizErrCode.ADDRESS_SYNC_PAGE_INVALID);
    }
}
