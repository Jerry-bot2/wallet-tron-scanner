package com.nb.tron.scanner.index;

import com.nb.chain.client.enums.AddressPurpose;
import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * TRON 平台地址内存索引
 * 负责保存和查询平台监控地址的内存快照
 * 简单理解TronAddressIndex = Scanner 的内存地址仓库
 *
 * <p>内存只保存地址与用途。启动加载完成后一次性替换索引状态，扫块线程不会看到半成品数据。</p>
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Component
public class TronAddressIndex {

    /**
     * 保存了当前 Scanner 使用的整份地址内存快照
     */
    private final AtomicReference<IndexState> stateRef = new AtomicReference<>(IndexState.notReady());

    /**
     * 查询地址用途。索引未就绪时拒绝读取，避免扫块过程漏掉平台地址。
     *
     * @param address TRON 地址
     * @return 地址用途；不是平台地址时返回 null
     */
    public AddressPurpose findPurpose(String address) {
        if (address == null) {
            return null;
        }
        return requireReadyState().addresses().get(address);
    }

    /**
     * 判断地址是否属于平台。
     */
    public boolean contains(String address) {
        return findPurpose(address) != null;
    }

    /**
     * 当前索引是否已经完成初始化。
     */
    public boolean isReady() {
        return stateRef.get().ready();
    }

    /**
     * 当前已经加载到内存的最大链服务地址ID。
     */
    public long getAppliedMaxAddressId() {
        return stateRef.get().appliedMaxAddressId();
    }

    /**
     * 当前内存地址数量。
     */
    public int size() {
        return stateRef.get().addresses().size();
    }

    /**
     * 原子替换完整地址索引，并同步推进内存应用水位。
     *
     * <p>调用后由本索引独占传入 Map，调用方不得继续修改。</p>
     */
    void replaceAll(ConcurrentMap<String, AddressPurpose> addresses, long appliedMaxAddressId) {
        BizAssert.notNull(addresses, ScannerBizErrCode.ADDRESS_INDEX_DATA_INVALID);
        BizAssert.isTrue(appliedMaxAddressId >= 0, ScannerBizErrCode.ADDRESS_INDEX_DATA_INVALID);
        stateRef.set(new IndexState(addresses, appliedMaxAddressId, true));
    }

    private IndexState requireReadyState() {
        IndexState state = stateRef.get();
        BizAssert.isTrue(state.ready(), ScannerBizErrCode.ADDRESS_INDEX_NOT_READY);
        return state;
    }

    private record IndexState(ConcurrentMap<String, AddressPurpose> addresses, long appliedMaxAddressId, boolean ready) {

        private static IndexState notReady() {
            return new IndexState(new ConcurrentHashMap<>(), 0L, false);
        }
    }
}
