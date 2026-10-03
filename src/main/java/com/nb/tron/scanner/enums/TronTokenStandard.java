package com.nb.tron.scanner.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TRON 资产标准
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Getter
@RequiredArgsConstructor
public enum TronTokenStandard {

    NATIVE("NATIVE"),

    TRC20("TRC20"),
    ;

    private final String code;

    public static TronTokenStandard fromCode(String code) {
        for (TronTokenStandard tokenStandard : values()) {
            if (tokenStandard.code.equals(code)) {
                return tokenStandard;
            }
        }
        return null;
    }
}
