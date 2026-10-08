package com.nb.tron.scanner.index;

import com.nb.chain.client.enums.AddressPurpose;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
class TronAddressIndexTest {

    @Test
    void shouldRejectReadsBeforeIndexIsReady() {
        TronAddressIndex addressIndex = new TronAddressIndex();

        assertThat(addressIndex.isReady()).isFalse();
        assertThatThrownBy(() -> addressIndex.contains("TAddress1"))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ScannerBizErrCode.ADDRESS_INDEX_NOT_READY);
        assertThatThrownBy(addressIndex::getAppliedMaxAddressId)
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ScannerBizErrCode.ADDRESS_INDEX_NOT_READY);
    }

    @Test
    void shouldAtomicallyReplaceCompleteAddressIndex() {
        TronAddressIndex addressIndex = new TronAddressIndex();
        ConcurrentMap<String, AddressPurpose> addresses = new ConcurrentHashMap<>();
        addresses.put("TAddress1", AddressPurpose.DEPOSIT);
        addresses.put("TAddress2", AddressPurpose.HOT);

        addressIndex.replaceAll(addresses, 20L);

        assertThat(addressIndex.isReady()).isTrue();
        assertThat(addressIndex.size()).isEqualTo(2);
        assertThat(addressIndex.getAppliedMaxAddressId()).isEqualTo(20L);
        assertThat(addressIndex.findPurpose("TAddress1")).isEqualTo(AddressPurpose.DEPOSIT);
        assertThat(addressIndex.contains("TAddress2")).isTrue();
        assertThat(addressIndex.contains("TUnknown")).isFalse();
    }

    @Test
    void shouldApplyAddressIncrementAndAdvanceWatermark() {
        TronAddressIndex addressIndex = new TronAddressIndex();
        ConcurrentMap<String, AddressPurpose> addresses = new ConcurrentHashMap<>();
        addresses.put("TAddress1", AddressPurpose.DEPOSIT);
        addressIndex.replaceAll(addresses, 10L);

        long appliedMaxAddressId = addressIndex.applyIncrement(Map.of(
            "TAddress11", AddressPurpose.DEPOSIT,
            "TAddress12", AddressPurpose.HOT), 12L);

        assertThat(appliedMaxAddressId).isEqualTo(12L);
        assertThat(addressIndex.findPurpose("TAddress11")).isEqualTo(AddressPurpose.DEPOSIT);
        assertThat(addressIndex.findPurpose("TAddress12")).isEqualTo(AddressPurpose.HOT);
        assertThat(addressIndex.getAppliedMaxAddressId()).isEqualTo(12L);
    }

    @Test
    void shouldNotApplyIncrementWhenExistingPurposeConflicts() {
        TronAddressIndex addressIndex = new TronAddressIndex();
        ConcurrentMap<String, AddressPurpose> addresses = new ConcurrentHashMap<>();
        addresses.put("TAddress1", AddressPurpose.DEPOSIT);
        addressIndex.replaceAll(addresses, 10L);

        assertThatThrownBy(() -> addressIndex.applyIncrement(Map.of(
                "TAddress1", AddressPurpose.HOT,
                "TAddress11", AddressPurpose.DEPOSIT), 11L))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.ADDRESS_INDEX_CONFLICT);
        assertThat(addressIndex.contains("TAddress11")).isFalse();
        assertThat(addressIndex.getAppliedMaxAddressId()).isEqualTo(10L);
    }
}
