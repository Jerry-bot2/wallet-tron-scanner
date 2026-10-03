package com.nb.tron.scanner.index;

import com.nb.tron.scanner.biz.CurrencySyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * TRON 币种配置启动加载器。
 *
 * <p>应用启动时完成首次同步。链服务不可用或配置不合法时启动失败，
 * 防止 Scanner 在没有币种配置的情况下漏掉充值。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
@RequiredArgsConstructor
public class TronCurrencyIndexLoader implements ApplicationRunner {

    private final CurrencySyncService currencySyncService;

    @Override
    public void run(ApplicationArguments args) {
        currencySyncService.syncCurrencies();
    }
}
