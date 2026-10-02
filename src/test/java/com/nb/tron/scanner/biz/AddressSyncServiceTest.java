package com.nb.tron.scanner.biz;

import com.nb.chain.client.enums.AddressPurpose;
import com.nb.chain.client.resp.ScannerAddressPageResp;
import com.nb.chain.client.resp.ScannerAddressResp;
import com.nb.core.exception.BizException;
import com.nb.mybatis.transaction.TransactionExecute;
import com.nb.mybatis.transaction.TransactionSupport;
import com.nb.tron.scanner.client.AddressSyncClient;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronMonitorAddress;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.service.ITronMonitorAddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
class AddressSyncServiceTest {

    private ITronMonitorAddressService monitorAddressService;

    private TransactionSupport transactionSupport;

    private TronAddressIndex addressIndex;

    private AddressSyncClient addressSyncClient;

    private AddressSyncService addressSyncService;

    @BeforeEach
    void setUp() {
        monitorAddressService = mock(ITronMonitorAddressService.class);
        transactionSupport = mock(TransactionSupport.class);
        addressIndex = mock(TronAddressIndex.class);
        addressSyncClient = mock(AddressSyncClient.class);
        doAnswer(invocation -> {
            TransactionExecute execute = invocation.getArgument(0);
            execute.run();
            return null;
        }).when(transactionSupport).execute(org.mockito.ArgumentMatchers.any(TransactionExecute.class));
        when(addressIndex.applyIncrement(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.anyLong()))
            .thenAnswer(invocation -> invocation.getArgument(1));
        addressSyncService = new AddressSyncService(
            new TronScannerProperties(),
            monitorAddressService,
            transactionSupport,
            addressIndex,
            addressSyncClient);
    }

    @Test
    void shouldSaveNewAddresses() {
        List<ScannerAddressResp> addresses = List.of(
            address(11L, "TAddress11"),
            address(12L, "TAddress12"));
        when(monitorAddressService.listBySourceIdsOrAddresses(eq("MAINNET"), anyList(), anyList()))
            .thenReturn(List.of());
        when(monitorAddressService.saveBatch(anyList())).thenReturn(true);

        addressSyncService.applyIncrement(page(addresses, 12L));

        verify(monitorAddressService).saveBatch(argThat(savedAddresses ->
            savedAddresses.stream()
                .map(TronMonitorAddress::getSourceAddressId)
                .toList()
                .equals(List.of(11L, 12L))));
        verify(addressIndex).applyIncrement(argThat(index -> index.keySet().containsAll(
            List.of("TAddress11", "TAddress12"))), eq(12L));
    }

    @Test
    void shouldAcceptExistingAddressesWithSameContent() {
        ScannerAddressResp address = address(11L, "TAddress11");
        when(monitorAddressService.listBySourceIdsOrAddresses(eq("MAINNET"), anyList(), anyList()))
            .thenReturn(List.of(monitorAddress(11L, "TAddress11")));

        addressSyncService.applyIncrement(page(List.of(address), 11L));

        verify(monitorAddressService, never()).saveBatch(anyList());
        verify(addressIndex).applyIncrement(argThat(index -> index.containsKey("TAddress11")), eq(11L));
    }

    @Test
    void shouldRejectExistingAddressWithDifferentSourceId() {
        ScannerAddressResp address = address(12L, "TAddress11");
        when(monitorAddressService.listBySourceIdsOrAddresses(eq("MAINNET"), anyList(), anyList()))
            .thenReturn(List.of(monitorAddress(11L, "TAddress11")));

        assertThatThrownBy(() -> addressSyncService.applyIncrement(page(List.of(address), 12L)))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.ADDRESS_SYNC_DATA_CONFLICT);
        verify(addressIndex, never()).applyIncrement(org.mockito.ArgumentMatchers.anyMap(), eq(12L));
    }

    @Test
    void shouldSyncAllAddressPagesAndAcknowledgeEachWatermark() {
        List<ScannerAddressResp> firstAddresses = List.of(
            address(11L, "TAddress11"),
            address(12L, "TAddress12"));
        List<ScannerAddressResp> secondAddresses = List.of(address(15L, "TAddress15"));
        when(addressIndex.getAppliedMaxAddressId()).thenReturn(10L);
        when(addressSyncClient.pullNextPage(10L)).thenReturn(page(firstAddresses, 12L, true));
        when(addressSyncClient.pullNextPage(12L)).thenReturn(page(secondAddresses, 15L, false));
        when(monitorAddressService.listBySourceIdsOrAddresses(eq("MAINNET"), anyList(), anyList()))
            .thenReturn(List.of());
        when(monitorAddressService.saveBatch(anyList())).thenReturn(true);

        int appliedCount = addressSyncService.syncAddresses();

        assertThat(appliedCount).isEqualTo(3);
        verify(addressSyncClient).acknowledge(12L);
        verify(addressSyncClient).acknowledge(15L);
        verify(addressSyncClient).pullNextPage(10L);
        verify(addressSyncClient).pullNextPage(12L);
    }

    @Test
    void shouldFinishWhenChainServerReturnsEmptyPage() {
        when(addressIndex.getAppliedMaxAddressId()).thenReturn(10L);
        when(addressSyncClient.pullNextPage(10L)).thenReturn(page(List.of(), 10L, false));

        int appliedCount = addressSyncService.syncAddresses();

        assertThat(appliedCount).isZero();
        InOrder callOrder = inOrder(addressSyncClient);
        callOrder.verify(addressSyncClient).pullNextPage(10L);
        callOrder.verify(addressSyncClient).acknowledge(10L);
        verify(addressIndex, never()).applyIncrement(
            org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void shouldReportAcknowledgementFailureAfterPullingEmptyPage() {
        RuntimeException ackFailure = new RuntimeException("ack failed");
        when(addressIndex.getAppliedMaxAddressId()).thenReturn(10L);
        when(addressSyncClient.pullNextPage(10L)).thenReturn(page(List.of(), 10L, false));
        doThrow(ackFailure).when(addressSyncClient).acknowledge(10L);

        assertThatThrownBy(addressSyncService::syncAddresses).isSameAs(ackFailure);

        verify(addressSyncClient).pullNextPage(10L);
    }

    @Test
    void shouldStopBeforePullingNextPageWhenAcknowledgementFails() {
        RuntimeException ackFailure = new RuntimeException("ack failed");
        when(addressIndex.getAppliedMaxAddressId()).thenReturn(10L);
        when(addressSyncClient.pullNextPage(10L))
            .thenReturn(page(List.of(address(12L, "TAddress12")), 12L, true));
        when(monitorAddressService.listBySourceIdsOrAddresses(eq("MAINNET"), anyList(), anyList()))
            .thenReturn(List.of());
        when(monitorAddressService.saveBatch(anyList())).thenReturn(true);
        doThrow(ackFailure).when(addressSyncClient).acknowledge(12L);

        assertThatThrownBy(addressSyncService::syncAddresses).isSameAs(ackFailure);

        verify(addressSyncClient, never()).pullNextPage(12L);
    }

    @Test
    void shouldRecoverFailedAcknowledgementOnNextSchedule() {
        RuntimeException ackFailure = new RuntimeException("ack failed");
        ScannerAddressPageResp addressIncrement = page(
            List.of(address(12L, "TAddress12")), 12L, false);
        when(addressIndex.getAppliedMaxAddressId()).thenReturn(10L, 12L);
        when(addressSyncClient.pullNextPage(10L)).thenReturn(addressIncrement);
        when(addressSyncClient.pullNextPage(12L)).thenReturn(page(List.of(), 12L, false));
        when(monitorAddressService.listBySourceIdsOrAddresses(eq("MAINNET"), anyList(), anyList()))
            .thenReturn(List.of());
        when(monitorAddressService.saveBatch(anyList())).thenReturn(true);
        doThrow(ackFailure).doNothing().when(addressSyncClient).acknowledge(12L);

        assertThatThrownBy(addressSyncService::syncAddresses).isSameAs(ackFailure);
        assertThat(addressSyncService.syncAddresses()).isZero();

        verify(addressSyncClient, times(2)).acknowledge(12L);
        verify(addressSyncClient).pullNextPage(12L);
    }

    private ScannerAddressPageResp page(List<ScannerAddressResp> addresses, long maxAddressId) {
        return page(addresses, maxAddressId, false);
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

    private TronMonitorAddress monitorAddress(long addressId, String address) {
        return new TronMonitorAddress()
            .setSourceAddressId(addressId)
            .setChainNetwork("MAINNET")
            .setAddress(address)
            .setAddressPurpose(AddressPurpose.DEPOSIT.getCode());
    }
}
