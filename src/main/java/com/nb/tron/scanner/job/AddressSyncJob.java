package com.nb.tron.scanner.job;

import com.nb.job.core.NbJobContext;
import com.nb.job.core.NbJobHandler;
import com.nb.tron.scanner.biz.AddressSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TRON 监控地址同步任务。
 *
 * <p>
 * 1.由 XXL-JOB 按单机串行方式触发；
 * 2.调用地址同步主流程；
 * 3.同步失败时记录地址池停滞告警，并把异常原样抛给 XXL-JOB；
 * 4.下一次调度继续从本地已经应用的安全水位同步。
 * </p>
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AddressSyncJob extends NbJobHandler {

    private final AddressSyncService addressSyncService;

    @Override
    protected Integer doExecute(NbJobContext ignored) {
        try {
            int syncedCount = addressSyncService.syncAddresses();
            log.info("TRON监控地址同步完成，syncedCount={}", syncedCount);
            return syncedCount;
        } catch (RuntimeException | Error exception) {
            // 地址不会丢失，但未获得 Scanner ACK 的新地址将一直保持不可分配。
            log.error("TRON监控地址同步失败，新增地址继续保持不可分配，等待下次调度重试，errorType={}，errorMessage={}", exception.getClass().getSimpleName(), exception.getMessage());
            throw exception;
        }
    }
}
