package com.nb.tron.scanner.job;

import com.nb.tron.scanner.biz.AddressSyncService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
class AddressSyncJobTest {

    private final AddressSyncService addressSyncService = mock(AddressSyncService.class);

    private final AddressSyncJob addressSyncJob = new AddressSyncJob(addressSyncService);

    @Test
    void shouldReturnSyncedAddressCount() {
        when(addressSyncService.syncAddresses()).thenReturn(3);

        Integer syncedCount = addressSyncJob.doExecute(null);

        assertThat(syncedCount).isEqualTo(3);
        verify(addressSyncService).syncAddresses();
    }

    @Test
    void shouldPropagateFailureForNextScheduleRetry() {
        RuntimeException syncFailure = new RuntimeException("sync failed");
        when(addressSyncService.syncAddresses()).thenThrow(syncFailure);

        assertThatThrownBy(() -> addressSyncJob.doExecute(null)).isSameAs(syncFailure);
    }
}
