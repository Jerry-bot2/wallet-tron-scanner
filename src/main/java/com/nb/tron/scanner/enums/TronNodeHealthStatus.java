package com.nb.tron.scanner.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TRON 节点健康状态
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Getter
@RequiredArgsConstructor
public enum TronNodeHealthStatus {

    UNKNOWN("UNKNOWN", "尚未确认可用"),
    HEALTHY("HEALTHY", "节点可用"),
    UNHEALTHY("UNHEALTHY", "节点不可用"),
    ;

    private final String code;

    private final String desc;
}
