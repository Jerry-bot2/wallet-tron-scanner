package com.nb.tron.scanner.node;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.sdk.enums.TronNodeHealthStatus;
import com.nb.tron.sdk.enums.TronNodeRole;
import com.nb.tron.sdk.node.TronNodePool;
import com.nb.tron.sdk.node.TronNodeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class TronNodeStartupValidatorTest {

    private TronNodePool nodePool;

    private TronNodeStartupValidator startupValidator;

    @BeforeEach
    void setUp() {
        nodePool = mock(TronNodePool.class);
        startupValidator = new TronNodeStartupValidator(nodePool);
    }

    @Test
    void shouldStartWhenFullNodeAndSolidityNodeAreHealthy() {
        when(nodePool.states()).thenReturn(List.of(
            healthyState("full-primary", TronNodeRole.FULL_NODE),
            healthyState("solidity-primary", TronNodeRole.SOLIDITY_NODE)));

        assertThatNoException().isThrownBy(startupValidator::validateConfiguredNodes);
        verify(nodePool).refresh();
    }

    @Test
    void shouldIgnoreUnhealthyNode() {
        when(nodePool.states()).thenReturn(List.of(
            unhealthyState("full-wrong", TronNodeRole.FULL_NODE),
            healthyState("full-primary", TronNodeRole.FULL_NODE)));

        assertThatNoException().isThrownBy(startupValidator::validateConfiguredNodes);
    }

    @Test
    void shouldFailStartupWhenNoFullNodeIsHealthy() {
        when(nodePool.states()).thenReturn(List.of(
            unhealthyState("full-primary", TronNodeRole.FULL_NODE),
            healthyState("solidity-primary", TronNodeRole.SOLIDITY_NODE)));

        assertThatThrownBy(startupValidator::validateConfiguredNodes)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_SDK_CALL_FAILED);
    }

    @Test
    void shouldAllowStartupWhenOnlySolidityNodeIsUnhealthy() {
        when(nodePool.states()).thenReturn(List.of(
            healthyState("full-primary", TronNodeRole.FULL_NODE),
            unhealthyState("solidity-primary", TronNodeRole.SOLIDITY_NODE)));

        assertThatNoException().isThrownBy(startupValidator::validateConfiguredNodes);
    }

    private TronNodeState healthyState(String nodeCode, TronNodeRole nodeRole) {
        return new TronNodeState(
            nodeCode,
            nodeRole,
            TronNodeHealthStatus.HEALTHY,
            100L,
            0,
            10L,
            Instant.now(),
            System.nanoTime(),
            null);
    }

    private TronNodeState unhealthyState(String nodeCode, TronNodeRole nodeRole) {
        return new TronNodeState(
            nodeCode,
            nodeRole,
            TronNodeHealthStatus.UNHEALTHY,
            null,
            3,
            10L,
            null,
            0L,
            null);
    }
}
