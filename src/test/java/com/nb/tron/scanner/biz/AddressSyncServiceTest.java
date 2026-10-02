package com.nb.tron.scanner.biz;

import com.nb.chain.client.enums.AddressPurpose;
import com.nb.chain.client.resp.ScannerAddressResp;
import com.nb.core.exception.BizException;
import com.nb.mybatis.transaction.TransactionExecute;
import com.nb.mybatis.transaction.TransactionSupport;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronMonitorAddress;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.service.ITronMonitorAddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
class AddressSyncServiceTest {

    private ITronMonitorAddressService monitorAddressService;

    private TransactionSupport transactionSupport;

    private AddressSyncService addressSyncService;

    @BeforeEach
    void setUp() {
        monitorAddressService = mock(ITronMonitorAddressService.class);
        transactionSupport = mock(TransactionSupport.class);
        doAnswer(invocation -> {
            TransactionExecute execute = invocation.getArgument(0);
            execute.run();
            return null;
        }).when(transactionSupport).execute(org.mockito.ArgumentMatchers.any(TransactionExecute.class));
        addressSyncService = new AddressSyncService(
            new TronScannerProperties(),
            monitorAddressService,
            transactionSupport);
    }

    @Test
    void shouldSaveNewAddresses() {
        List<ScannerAddressResp> addresses = List.of(
            address(11L, "TAddress11"),
            address(12L, "TAddress12"));
        when(monitorAddressService.listBySourceIdsOrAddresses(eq("MAINNET"), anyList(), anyList()))
            .thenReturn(List.of());
        when(monitorAddressService.saveBatch(anyList())).thenReturn(true);

        addressSyncService.saveAddresses(addresses);

        verify(monitorAddressService).saveBatch(argThat(savedAddresses ->
            savedAddresses.stream()
                .map(TronMonitorAddress::getSourceAddressId)
                .toList()
                .equals(List.of(11L, 12L))));
    }

    @Test
    void shouldAcceptExistingAddressesWithSameContent() {
        ScannerAddressResp address = address(11L, "TAddress11");
        when(monitorAddressService.listBySourceIdsOrAddresses(eq("MAINNET"), anyList(), anyList()))
            .thenReturn(List.of(monitorAddress(11L, "TAddress11")));

        addressSyncService.saveAddresses(List.of(address));

        verify(monitorAddressService, never()).saveBatch(anyList());
    }

    @Test
    void shouldRejectExistingAddressWithDifferentSourceId() {
        ScannerAddressResp address = address(12L, "TAddress11");
        when(monitorAddressService.listBySourceIdsOrAddresses(eq("MAINNET"), anyList(), anyList()))
            .thenReturn(List.of(monitorAddress(11L, "TAddress11")));

        assertThatThrownBy(() -> addressSyncService.saveAddresses(List.of(address)))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.ADDRESS_SYNC_DATA_CONFLICT);
    }

    private ScannerAddressResp address(long addressId, String address) {
        return new ScannerAddressResp()
            .setAddressId(addressId)
            .setAddress(address)
            .setAddressPurpose(AddressPurpose.DEPOSIT);
    }

    private TronMonitorAddress monitorAddress(long addressId, String address) {
        return new TronMonitorAddress()
            .setSourceAddressId(addressId)
            .setChainNetwork("MAINNET")
            .setAddress(address)
            .setAddressPurpose(AddressPurpose.DEPOSIT.getCode());
    }
}
