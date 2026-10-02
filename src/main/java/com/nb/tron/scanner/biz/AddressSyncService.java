package com.nb.tron.scanner.biz;

import com.nb.chain.client.enums.AddressPurpose;
import com.nb.chain.client.resp.ScannerAddressPageResp;
import com.nb.chain.client.resp.ScannerAddressResp;
import com.nb.core.exception.BizAssert;
import com.nb.mybatis.transaction.TransactionSupport;
import com.nb.tron.scanner.client.AddressSyncClient;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronMonitorAddress;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
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
 * <p>负责将链服务地址幂等保存到本地数据库，并增量应用到内存索引。</p>
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

    private final TronAddressIndex addressIndex;

    private final AddressSyncClient addressSyncClient;

    /**
     * 增量同步平台监控地址。
     *
     * <p>1.按本地水位拉取增量；2.逐页保存数据库并写入内存；3.向链服务确认新水位。</p>
     *
     * @return 本轮成功同步的地址数量
     */
    public int syncAddresses() {
        long appliedMaxAddressId = addressIndex.getAppliedMaxAddressId();
        int syncedCount = 0;

        while (true) {
            ScannerAddressPageResp addressIncrement = addressSyncClient.pullNextPage(appliedMaxAddressId);
            if (addressIncrement.getAddresses().isEmpty()) {
                // 没有新增地址时，重复确认当前安全水位，用于恢复上次失败的 ACK。
                addressSyncClient.acknowledge(appliedMaxAddressId);
                return syncedCount;
            }

            appliedMaxAddressId = applyIncrement(addressIncrement);
            addressSyncClient.acknowledge(appliedMaxAddressId);
            syncedCount += addressIncrement.getAddresses().size();

            if (Boolean.TRUE.equals(addressIncrement.getHasMore())) {
                continue;
            }
            return syncedCount;
        }
    }

    /**
     * 持久化并应用一批增量监控地址。
     *
     * <p>先在本地事务中幂等保存地址，事务提交后再更新内存索引和应用水位。</p>
     */
    long applyIncrement(ScannerAddressPageResp addressIncrement) {
        List<ScannerAddressResp> addresses = addressIncrement.getAddresses();
        List<TronMonitorAddress> monitorAddresses = toMonitorAddresses(addresses);
        transactionSupport.execute(() -> persistAddresses(monitorAddresses));
        return addressIndex.applyIncrement(toAddressIndex(addresses), addressIncrement.getMaxAddressId());
    }

    private Map<String, AddressPurpose> toAddressIndex(List<ScannerAddressResp> addresses) {
        Map<String, AddressPurpose> purposeByAddress = new HashMap<>(addresses.size());
        for (ScannerAddressResp address : addresses) {
            purposeByAddress.put(address.getAddress(), address.getAddressPurpose());
        }
        return purposeByAddress;
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
