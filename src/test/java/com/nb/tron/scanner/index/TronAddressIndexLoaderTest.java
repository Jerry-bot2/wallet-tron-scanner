package com.nb.tron.scanner.index;

import com.nb.chain.client.enums.AddressPurpose;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronMonitorAddress;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.service.ITronMonitorAddressService;
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
class TronAddressIndexLoaderTest {

    private TronScannerProperties scannerProperties;

    private ITronMonitorAddressService monitorAddressService;

    private TronAddressIndex addressIndex;

    private TronAddressIndexLoader addressIndexLoader;

    @BeforeEach
    void setUp() {
        scannerProperties = new TronScannerProperties();
        monitorAddressService = mock(ITronMonitorAddressService.class);
        addressIndex = new TronAddressIndex();
        addressIndexLoader = new TronAddressIndexLoader(
                scannerProperties,
                monitorAddressService,
                addressIndex);
    }

    @Test
    void shouldLoadCurrentNetworkAddressesAndPublishIndex() {
        when(monitorAddressService.listByNetwork("MAINNET"))
                .thenReturn(List.of(
                        address(11L, "TAddress11", AddressPurpose.DEPOSIT),
                        address(15L, "TAddress15", AddressPurpose.RESOURCE)));

        addressIndexLoader.reload();

        verify(monitorAddressService).listByNetwork("MAINNET");
        assertThat(addressIndex.isReady()).isTrue();
        assertThat(addressIndex.size()).isEqualTo(2);
        assertThat(addressIndex.getAppliedMaxAddressId()).isEqualTo(15L);
        assertThat(addressIndex.findPurpose("TAddress15")).isEqualTo(AddressPurpose.RESOURCE);
    }

    @Test
    void shouldKeepPreviousSnapshotWhenReloadContainsInvalidData() {
        when(monitorAddressService.listByNetwork("MAINNET"))
                .thenReturn(List.of(address(11L, "TAddress11", AddressPurpose.DEPOSIT)));
        addressIndexLoader.reload();

        when(monitorAddressService.listByNetwork("MAINNET"))
                .thenReturn(List.of(address(12L, "TAddress12", null)));

        assertThatThrownBy(addressIndexLoader::reload)
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ScannerBizErrCode.ADDRESS_INDEX_DATA_INVALID);
        assertThat(addressIndex.isReady()).isTrue();
        assertThat(addressIndex.size()).isEqualTo(1);
        assertThat(addressIndex.getAppliedMaxAddressId()).isEqualTo(11L);
        assertThat(addressIndex.findPurpose("TAddress11")).isEqualTo(AddressPurpose.DEPOSIT);
    }

    private TronMonitorAddress address(long sourceAddressId,
                                       String address,
                                       AddressPurpose addressPurpose) {
        return new TronMonitorAddress()
                .setSourceAddressId(sourceAddressId)
                .setChainNetwork("MAINNET")
                .setAddress(address)
                .setAddressPurpose(addressPurpose == null ? "UNKNOWN" : addressPurpose.getCode());
    }
}
