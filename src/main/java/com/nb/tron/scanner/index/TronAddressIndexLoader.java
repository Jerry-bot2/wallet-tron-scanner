package com.nb.tron.scanner.index;

import com.nb.chain.client.enums.AddressPurpose;
import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronMonitorAddress;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.service.ITronMonitorAddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * TRON 地址索引启动加载器
 * 负责将 Scanner 本地数据库中的监控地址加载到内存
 * TronAddressIndexLoader = 负责向内存仓库装数据的人
 *
 * <p>先从本地数据库构建完整索引，全部校验成功后再原子替换内存状态。</p>
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TronAddressIndexLoader implements ApplicationRunner {

    private final TronScannerProperties scannerProperties;

    private final ITronMonitorAddressService monitorAddressService;

    private final TronAddressIndex addressIndex;

    @Override
    public void run(ApplicationArguments args) {
        reload();
    }

    /**
     * 从本地数据库重建当前网络的完整地址索引
     */
    public void reload() {
        String chainNetwork = scannerProperties.getChainNetwork();
        BizAssert.isTrue(StringUtils.hasText(chainNetwork), ScannerBizErrCode.SCANNER_RUNTIME_CONFIG_INVALID);

        List<TronMonitorAddress> monitorAddresses = monitorAddressService.listByNetwork(chainNetwork);
        LoadedAddressIndex loadedIndex = buildIndex(chainNetwork, monitorAddresses);
        addressIndex.replaceAll(loadedIndex.addresses(), loadedIndex.appliedMaxAddressId());

        log.info("TRON地址索引加载完成，chainNetwork={}，addressCount={}，appliedMaxAddressId={}",
            chainNetwork,
            loadedIndex.addresses().size(),
            loadedIndex.appliedMaxAddressId());
    }

    /**
     * 负责把数据库查询出来的地址列表转换成适合内存查询的地址索引
     */
    private LoadedAddressIndex buildIndex(String chainNetwork, List<TronMonitorAddress> monitorAddresses) {
        ConcurrentMap<String, AddressPurpose> purposeByAddress = new ConcurrentHashMap<>(monitorAddresses.size());
        long maxSourceAddressId = 0L;

        for (TronMonitorAddress monitorAddress : monitorAddresses) {
            AddressPurpose addressPurpose = validateAddress(chainNetwork, monitorAddress);
            AddressPurpose existingPurpose =
                purposeByAddress.putIfAbsent(monitorAddress.getAddress(), addressPurpose);
            BizAssert.isTrue(existingPurpose == null, ScannerBizErrCode.ADDRESS_INDEX_DUPLICATE);
            maxSourceAddressId = Math.max(maxSourceAddressId, monitorAddress.getSourceAddressId());
        }
        return new LoadedAddressIndex(purposeByAddress, maxSourceAddressId);
    }

    private AddressPurpose validateAddress(String chainNetwork, TronMonitorAddress address) {
        boolean validAddress = address != null
            && address.getSourceAddressId() != null
            && address.getSourceAddressId() > 0
            && Objects.equals(chainNetwork, address.getChainNetwork())
            && StringUtils.hasText(address.getAddress());
        BizAssert.isTrue(validAddress, ScannerBizErrCode.ADDRESS_INDEX_DATA_INVALID);

        AddressPurpose addressPurpose = AddressPurpose.fromCode(address.getAddressPurpose());
        BizAssert.notNull(addressPurpose, ScannerBizErrCode.ADDRESS_INDEX_DATA_INVALID);
        return addressPurpose;
    }

    private record LoadedAddressIndex(ConcurrentMap<String, AddressPurpose> addresses, long appliedMaxAddressId) {
    }
}
