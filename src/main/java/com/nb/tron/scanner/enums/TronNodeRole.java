package com.nb.tron.scanner.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * TRON 节点角色
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Getter
@RequiredArgsConstructor
public enum TronNodeRole {

    FULL_NODE("FULL_NODE", "最新 Head 视图"),
    SOLIDITY_NODE("SOLIDITY_NODE", "固化数据视图"),
    ;

    private final String code;

    private final String desc;
}
