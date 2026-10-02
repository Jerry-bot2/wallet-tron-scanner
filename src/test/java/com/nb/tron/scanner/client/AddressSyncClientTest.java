package com.nb.tron.scanner.client;

import com.nb.chain.client.api.ChainScannerClient;
import com.nb.chain.client.enums.AddressPurpose;
import com.nb.chain.client.resp.ScannerAddressPageResp;
import com.nb.chain.client.resp.ScannerAddressResp;
import com.nb.core.exception.BizException;
import com.nb.core.response.Result;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
        addressSyncClient = new AddressSyncClient(
            chainScannerClient,
            new TronScannerProperties());
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

    private ScannerAddressPageResp page(List<ScannerAddressResp> addresses,
                                        long maxAddressId,
                                        boolean hasMore) {
        return new ScannerAddressPageResp()
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
