package com.nb.tron.scanner.biz;

import com.nb.chain.client.resp.ScannerAddressResp;
import com.nb.core.exception.BizAssert;
import com.nb.mybatis.transaction.TransactionSupport;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronMonitorAddress;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.service.ITronMonitorAddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * TRON 监控地址同步服务。
 *
 * <p>当前阶段负责将链服务地址幂等保存到扫描器本地数据库。</p>
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Service
@RequiredArgsConstructor
public class AddressSyncService {

    private final TronScannerProperties scannerProperties;

    private final ITronMonitorAddressService monitorAddressService;

    private final TransactionSupport transactionSupport;

    /**
     * 幂等保存一页监控地址。
     *
     * <p>本地记录与同步数据完全一致时视为成功；相同ID或地址对应不同数据时终止同步。</p>
     */
    public void saveAddresses(List<ScannerAddressResp> addresses) {
        if (addresses.isEmpty()) {
            return;
        }

        List<TronMonitorAddress> monitorAddresses = toMonitorAddresses(addresses);
        transactionSupport.execute(() -> persistAddresses(monitorAddresses));
    }

    private void persistAddresses(List<TronMonitorAddress> monitorAddresses) {
        List<TronMonitorAddress> existingAddresses = findExistingAddresses(monitorAddresses);
        List<TronMonitorAddress> newAddresses = resolveNewAddresses(monitorAddresses, existingAddresses);

        if (!newAddresses.isEmpty()) {
            BizAssert.isTrue(monitorAddressService.saveBatch(newAddresses), ScannerBizErrCode.ADDRESS_SYNC_SAVE_FAILED);
        }
    }

    private List<TronMonitorAddress> toMonitorAddresses(List<ScannerAddressResp> addresses) {
        String chainNetwork = scannerProperties.getChainNetwork();
        return addresses.stream()
            .map(address -> new TronMonitorAddress()
                .setSourceAddressId(address.getAddressId())
                .setChainNetwork(chainNetwork)
                .setAddress(address.getAddress())
                .setAddressPurpose(address.getAddressPurpose().getCode()))
            .toList();
    }

    private List<TronMonitorAddress> findExistingAddresses(List<TronMonitorAddress> monitorAddresses) {
        List<Long> sourceAddressIds = monitorAddresses.stream()
            .map(TronMonitorAddress::getSourceAddressId)
            .toList();
        List<String> addresses = monitorAddresses.stream()
            .map(TronMonitorAddress::getAddress)
            .toList();
        return monitorAddressService.listBySourceIdsOrAddresses(
            scannerProperties.getChainNetwork(),
            sourceAddressIds,
            addresses);
    }

    private List<TronMonitorAddress> resolveNewAddresses(List<TronMonitorAddress> monitorAddresses,
                                                         List<TronMonitorAddress> existingAddresses) {
        Map<Long, TronMonitorAddress> existingById = new HashMap<>();
        Map<String, TronMonitorAddress> existingByAddress = new HashMap<>();
        String chainNetwork = scannerProperties.getChainNetwork();

        for (TronMonitorAddress existingAddress : existingAddresses) {
            existingById.put(existingAddress.getSourceAddressId(), existingAddress);
            if (Objects.equals(chainNetwork, existingAddress.getChainNetwork())) {
                existingByAddress.put(existingAddress.getAddress(), existingAddress);
            }
        }

        List<TronMonitorAddress> newAddresses = new ArrayList<>();
        for (TronMonitorAddress monitorAddress : monitorAddresses) {
            TronMonitorAddress existingWithSameId = existingById.get(monitorAddress.getSourceAddressId());
            TronMonitorAddress existingWithSameAddress = existingByAddress.get(monitorAddress.getAddress());
            if (existingWithSameId == null && existingWithSameAddress == null) {
                newAddresses.add(monitorAddress);
                continue;
            }
            BizAssert.isTrue(isSameAddress(monitorAddress, existingWithSameId, existingWithSameAddress), ScannerBizErrCode.ADDRESS_SYNC_DATA_CONFLICT);
        }
        return newAddresses;
    }

    private boolean isSameAddress(TronMonitorAddress monitorAddress,
                                  TronMonitorAddress existingWithSameId,
                                  TronMonitorAddress existingWithSameAddress) {
        return existingWithSameId != null
            && existingWithSameAddress != null
            && Objects.equals(existingWithSameId.getSourceAddressId(), existingWithSameAddress.getSourceAddressId())
            && Objects.equals(monitorAddress.getSourceAddressId(), existingWithSameId.getSourceAddressId())
            && Objects.equals(monitorAddress.getChainNetwork(), existingWithSameId.getChainNetwork())
            && Objects.equals(monitorAddress.getAddress(), existingWithSameId.getAddress())
            && Objects.equals(monitorAddress.getAddressPurpose(), existingWithSameId.getAddressPurpose());
    }
}
