package com.nb.tron.scanner.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * <p>
 * 扫描器监控地址用途
 * </p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Getter
@RequiredArgsConstructor
public enum AddressPurpose {

    DEPOSIT("DEPOSIT", "用户充值地址"),
    HOT("HOT", "平台热钱包地址"),
    RESOURCE("RESOURCE", "平台资源钱包地址"),
    ;

    private final String code;
    private final String desc;

    public static AddressPurpose fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        for (AddressPurpose value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}
