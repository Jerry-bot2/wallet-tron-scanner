package com.nb.tron.scanner.config;

import com.nb.core.exception.BizAssert;
import com.nb.tron.sdk.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * TRON 扫描器运行配置
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "nb.tron.scanner")
public class TronScannerProperties {

    /**
     * 当前扫描器连接的 TRON 网络，例如 MAINNET、NILE
     */
    private String chainNetwork = "MAINNET";

    /**
     * 当前网络创世区块 ID，用于防止节点连接到错误网络。
     */
    private String expectedGenesisBlockId;

    /**
     * 首次扫描的起始高度，仅首次初始化检查点时使用
     */
    private Long startBlockHeight;

    /**
     * 单次扫块任务最多顺序处理的区块数量
     */
    private int maxBlocksPerRun = 100;

    /**
     * 发送充值事件后等待 Kafka Broker 确认的最长时间
     */
    private Duration kafkaAckTimeout = Duration.ofSeconds(30);

    /**
     * TRON 节点访问策略及节点列表
     */
    private final TronNodeProperties node = new TronNodeProperties();

    /**
     * 校验 Scanner 运行参数和节点静态配置
     */
    @PostConstruct
    public void validate() {
        validateRuntime();
        validateNodePolicy();
        validateNodes();
    }

    private void validateRuntime() {
        BizAssert.hasText(chainNetwork, ScannerBizErrCode.SCANNER_RUNTIME_CONFIG_INVALID);
        BizAssert.isTrue(startBlockHeight != null && startBlockHeight >= 0, ScannerBizErrCode.SCANNER_RUNTIME_CONFIG_INVALID);
        BizAssert.isTrue(maxBlocksPerRun > 0, ScannerBizErrCode.SCANNER_RUNTIME_CONFIG_INVALID);
        BizAssert.isTrue(isPositive(kafkaAckTimeout) && kafkaAckTimeout.toMillis() > 0,
            ScannerBizErrCode.SCANNER_RUNTIME_CONFIG_INVALID);
        BizAssert.hasText(expectedGenesisBlockId, ScannerBizErrCode.TRON_SDK_CONFIG_INVALID);
    }

    private void validateNodePolicy() {
        boolean validTimeouts = isPositive(node.getConnectTimeout())
            && isPositive(node.getReadTimeout())
            && isPositive(node.getHealthCheckInterval())
            && isPositive(node.getRecoveryCooldown());
        boolean validThresholds = node.getFailureThreshold() > 0 && node.getHeightLagThreshold() >= 0;
        boolean validResponseSize = node.getMaxResponseSize() != null
            && node.getMaxResponseSize().toBytes() > 0
            && node.getMaxResponseSize().toBytes() < Integer.MAX_VALUE;

        BizAssert.isTrue(validTimeouts && validThresholds && validResponseSize, ScannerBizErrCode.TRON_SDK_CONFIG_INVALID);
    }

    private void validateNodes() {
        List<TronNodeEndpointProperties> nodes = node.getNodes();
        BizAssert.notEmpty(nodes, ScannerBizErrCode.TRON_SDK_CONFIG_INVALID);
        boolean fullNodeConfigured = nodes.stream()
            .anyMatch(endpoint -> endpoint != null && endpoint.getRole() == TronNodeRole.FULL_NODE);
        boolean solidityNodeConfigured = nodes.stream()
            .anyMatch(endpoint -> endpoint != null && endpoint.getRole() == TronNodeRole.SOLIDITY_NODE);
        BizAssert.isTrue(fullNodeConfigured && solidityNodeConfigured, ScannerBizErrCode.TRON_SDK_CONFIG_INVALID);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

}
