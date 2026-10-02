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
 * 3.异常原样抛出，由下一次调度从本地安全水位继
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
        int syncedCount = addressSyncService.syncAddresses();
        log.info("TRON监控地址同步完成，syncedCount={}", syncedCount);
        return syncedCount;
    }
}
