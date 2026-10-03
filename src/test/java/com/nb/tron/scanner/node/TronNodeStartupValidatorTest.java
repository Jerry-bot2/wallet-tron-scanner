package com.nb.tron.scanner.node;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronNodeHealthStatus;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronNodeRuntimeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class TronNodeStartupValidatorTest {

    private TronNodeHealthService nodeHealthService;

    private TronNodeStartupValidator startupValidator;

    @BeforeEach
    void setUp() {
        TronScannerProperties scannerProperties = new TronScannerProperties();
        nodeHealthService = mock(TronNodeHealthService.class);
        startupValidator = new TronNodeStartupValidator(scannerProperties, nodeHealthService);
    }

    @Test
    void shouldStartWhenFullNodeAndSolidityNodeAreHealthy() {
        when(nodeHealthService.refreshNodeStates()).thenReturn(List.of(
            healthyState("full-primary", TronNodeRole.FULL_NODE),
            healthyState("solidity-primary", TronNodeRole.SOLIDITY_NODE)));

        assertThatNoException().isThrownBy(startupValidator::validateConfiguredNodes);
    }

    @Test
    void shouldIgnoreUnhealthyNode() {
        when(nodeHealthService.refreshNodeStates()).thenReturn(List.of(
            unhealthyState("full-wrong", TronNodeRole.FULL_NODE),
            healthyState("full-primary", TronNodeRole.FULL_NODE)));

        assertThatNoException().isThrownBy(startupValidator::validateConfiguredNodes);
    }

    @Test
    void shouldFailStartupWhenNoFullNodeIsHealthy() {
        when(nodeHealthService.refreshNodeStates()).thenReturn(List.of(
            unhealthyState("full-primary", TronNodeRole.FULL_NODE),
            healthyState("solidity-primary", TronNodeRole.SOLIDITY_NODE)));

        assertThatThrownBy(startupValidator::validateConfiguredNodes)
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_UNAVAILABLE);
    }

    @Test
    void shouldAllowStartupWhenOnlySolidityNodeIsUnhealthy() {
        when(nodeHealthService.refreshNodeStates()).thenReturn(List.of(
            healthyState("full-primary", TronNodeRole.FULL_NODE),
            unhealthyState("solidity-primary", TronNodeRole.SOLIDITY_NODE)));

        assertThatNoException().isThrownBy(startupValidator::validateConfiguredNodes);
    }

    private TronNodeRuntimeState healthyState(String nodeCode, TronNodeRole nodeRole) {
        return new TronNodeRuntimeState(
            nodeCode,
            nodeRole,
            TronNodeHealthStatus.HEALTHY,
            100L,
            0,
            10L,
            Instant.now());
    }

    private TronNodeRuntimeState unhealthyState(String nodeCode, TronNodeRole nodeRole) {
        return new TronNodeRuntimeState(
            nodeCode,
            nodeRole,
            TronNodeHealthStatus.UNHEALTHY,
            null,
            3,
            10L,
            null);
    }
}
