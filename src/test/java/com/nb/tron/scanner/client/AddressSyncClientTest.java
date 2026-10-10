package com.nb.tron.scanner.client;

import com.nb.chain.client.api.ChainScannerClient;
import com.nb.chain.client.enums.AddressPurpose;
import com.nb.chain.client.req.ScannerAddressAckReq;
import com.nb.chain.client.resp.ScannerAddressPageResp;
import com.nb.chain.client.resp.ScannerAddressResp;
import com.nb.core.exception.BizException;
import com.nb.core.response.Result;
import com.nb.tron.scanner.constant.TronConstants;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
class AddressSyncClientTest {

    private ChainScannerClient chainScannerClient;

    private AddressSyncClient addressSyncClient;

    @BeforeEach
    void setUp() {
        chainScannerClient = mock(ChainScannerClient.class);
        addressSyncClient = new AddressSyncClient(chainScannerClient);
    }

    @Test
    void shouldQueryAddressesAfterSpecifiedAddressId() {
        ScannerAddressPageResp addressPage = page(
            List.of(address(11L, "TAddress11"), address(15L, "TAddress15")),
            15L,
            true);
        when(chainScannerClient.listAddresses("TRON", 10L)).thenReturn(Result.success(addressPage));

        ScannerAddressPageResp result = addressSyncClient.pullNextPage(10L);

        assertThat(result).isSameAs(addressPage);
        verify(chainScannerClient).listAddresses("TRON", 10L);
    }

    @Test
    void shouldRejectAddressesThatAreNotStrictlyOrdered() {
        ScannerAddressPageResp addressPage = page(
            List.of(address(12L, "TAddress12"), address(11L, "TAddress11")),
            11L,
            false);
        when(chainScannerClient.listAddresses("TRON", 10L)).thenReturn(Result.success(addressPage));

        assertThatThrownBy(() -> addressSyncClient.pullNextPage(10L))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.ADDRESS_SYNC_PAGE_INVALID);
    }

    @Test
    void shouldAcknowledgeAppliedAddressWatermark() {
        when(chainScannerClient.ackAddressWatermark(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Result.success());

        addressSyncClient.acknowledge(15L);

        ArgumentCaptor<ScannerAddressAckReq> requestCaptor = ArgumentCaptor.forClass(ScannerAddressAckReq.class);
        verify(chainScannerClient).ackAddressWatermark(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getChainCode()).isEqualTo(TronConstants.CHAIN_CODE);
        assertThat(requestCaptor.getValue().getAppliedMaxAddressId()).isEqualTo(15L);
    }

    @Test
    void shouldRejectFailedAddressWatermarkAcknowledgement() {
        Result<Void> failedResult = Result.<Void>failBuilder()
            .code("500")
            .msg("failed")
            .build();
        when(chainScannerClient.ackAddressWatermark(org.mockito.ArgumentMatchers.any()))
            .thenReturn(failedResult);

        assertThatThrownBy(() -> addressSyncClient.acknowledge(15L))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.ADDRESS_SYNC_ACK_FAILED);
    }

    @Test
    void shouldRejectAddressPageFromAnotherChain() {
        ScannerAddressPageResp addressPage = page(List.of(), 10L, false)
            .setChainCode("ETH");
        when(chainScannerClient.listAddresses("TRON", 10L)).thenReturn(Result.success(addressPage));

        assertThatThrownBy(() -> addressSyncClient.pullNextPage(10L))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.ADDRESS_SYNC_PAGE_INVALID);
    }

    private ScannerAddressPageResp page(List<ScannerAddressResp> addresses,
                                        long maxAddressId,
                                        boolean hasMore) {
        return new ScannerAddressPageResp()
            .setChainCode("TRON")
            .setAddresses(addresses)
            .setMaxAddressId(maxAddressId)
            .setHasMore(hasMore);
    }

    private ScannerAddressResp address(long addressId, String address) {
        return new ScannerAddressResp()
            .setAddressId(addressId)
            .setAddress(address)
            .setAddressPurpose(AddressPurpose.DEPOSIT);
    }
}
