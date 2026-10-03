package com.nb.tron.scanner.job;

import com.nb.job.core.NbJobContext;
import com.nb.job.core.NbJobHandler;
import com.nb.tron.scanner.biz.CurrencySyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TRON 币种配置同步任务。
 *
 * <p>由 XXL-JOB 定时触发。同步失败时异常原样抛出，内存继续使用上一版完整快照。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CurrencySyncJob extends NbJobHandler {

    private final CurrencySyncService currencySyncService;

    @Override
    protected Integer doExecute(NbJobContext ignored) {
        int currencyCount = currencySyncService.syncCurrencies();
        log.info("TRON币种配置定时同步完成，currencyCount={}", currencyCount);
        return currencyCount;
    }
}
