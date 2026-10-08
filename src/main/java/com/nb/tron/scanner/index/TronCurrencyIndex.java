package com.nb.tron.scanner.index;

import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import com.nb.tron.sdk.model.TronAsset;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TRON 币种配置内存索引。
 *
 * <p>保存完整且不可变的币种快照。新配置全部校验通过后一次性替换，
 * 扫块线程不会读取到半份新配置。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
public class TronCurrencyIndex {

    private final AtomicReference<IndexState> stateRef = new AtomicReference<>(IndexState.notReady());

    /**
     * 查询原生币配置；未配置原生币时返回 null
     */
    public TronCurrencyConfig findNativeCurrency() {
        return requireReadyState().currencies().get(TronAsset.trx());
    }

    /**
     * 按合约地址查询 TRC20 币种；不是受支持合约时返回 null
     */
    public TronCurrencyConfig findByContractAddress(String contractAddress) {
        if (contractAddress == null) {
            return null;
        }
        return requireReadyState().currencies().get(TronAsset.trc20(contractAddress));
    }

    /**
     * 一轮解析和事件组装共用这份不可变资产快照，同步更新不会改变已取出的快照。
     */
    public Map<TronAsset, TronCurrencyConfig> snapshot() {
        return requireReadyState().currencies();
    }

    public boolean isReady() {
        return stateRef.get().ready();
    }

    public int size() {
        return stateRef.get().currencies().size();
    }

    /**
     * 原子替换完整币种快照
     *
     * <p>先构建完整的新快照，全部配置校验成功后再一次性替换旧快照。</p>
     */
    public void replaceAll(List<TronCurrencyConfig> currencies) {
        IndexState newState = buildIndexState(currencies);
        stateRef.set(newState);
    }

    /**
     * 构建新的完整索引。
     *
     * <p>原生币使用 NATIVE 身份，TRC20 使用资产标准和合约地址作为身份。</p>
     */
    private IndexState buildIndexState(List<TronCurrencyConfig> currencies) {
        BizAssert.notEmpty(currencies, ScannerBizErrCode.CURRENCY_CONFIG_INVALID);

        Map<TronAsset, TronCurrencyConfig> currencyByIdentity = new HashMap<>(currencies.size());
        for (TronCurrencyConfig currency : currencies) {
            TronCurrencyConfig previous = currencyByIdentity.putIfAbsent(
                currency.asset(),
                currency);
            BizAssert.isTrue(previous == null, ScannerBizErrCode.CURRENCY_CONFIG_DUPLICATE);
        }
        return IndexState.ready(currencyByIdentity);
    }

    private IndexState requireReadyState() {
        IndexState state = stateRef.get();
        BizAssert.isTrue(state.ready(), ScannerBizErrCode.CURRENCY_INDEX_NOT_READY);
        return state;
    }

    private record IndexState(Map<TronAsset, TronCurrencyConfig> currencies, boolean ready) {

        private static IndexState ready(Map<TronAsset, TronCurrencyConfig> currencies) {
            return new IndexState(Map.copyOf(currencies), true);
        }

        private static IndexState notReady() {
            return new IndexState(Map.of(), false);
        }
    }
}
