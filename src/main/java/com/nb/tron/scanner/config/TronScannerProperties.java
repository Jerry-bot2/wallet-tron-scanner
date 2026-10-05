package com.nb.tron.scanner.config;

import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.constant.TronConstants;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
     * 当前扫描器处理的链编码
     */
    private String chainCode = TronConstants.CHAIN_CODE;

    /**
     * 当前扫描器连接的 TRON 网络，例如 MAINNET、NILE
     */
    private String chainNetwork = "MAINNET";

    /**
     * 当前网络创世区块 ID，用于防止节点连接到错误网络。
     */
    private String expectedGenesisBlockId;

    /**
     * 本地没有扫描检查点时，第一个需要扫描的区块高度
     */
    private Long startBlockHeight;

    /**
     * 单次扫块任务最多顺序处理的区块数量
     */
    private int maxBlocksPerRun = 100;

    /**
     * SolidityNode 不可用时，固定重新扫描的最近 Head 区块数量
     */
    private int recheckWindow = 100;

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
        BizAssert.isTrue(TronConstants.CHAIN_CODE.equals(chainCode), ScannerBizErrCode.SCANNER_RUNTIME_CONFIG_INVALID);
        BizAssert.hasText(chainNetwork, ScannerBizErrCode.SCANNER_RUNTIME_CONFIG_INVALID);
        BizAssert.isTrue(startBlockHeight != null && startBlockHeight >= 0, ScannerBizErrCode.SCANNER_RUNTIME_CONFIG_INVALID);
        BizAssert.isTrue(maxBlocksPerRun > 0, ScannerBizErrCode.SCANNER_RUNTIME_CONFIG_INVALID);
        BizAssert.isTrue(recheckWindow > 0, ScannerBizErrCode.SCANNER_RUNTIME_CONFIG_INVALID);
        BizAssert.hasText(expectedGenesisBlockId, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
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

        BizAssert.isTrue(validTimeouts && validThresholds && validResponseSize, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
    }

    private void validateNodes() {
        List<TronNodeEndpointProperties> nodes = node.getNodes();
        BizAssert.notEmpty(nodes, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);

        Set<String> nodeCodes = new HashSet<>();
        Set<TronNodeRole> nodeRoles = new HashSet<>();
        for (TronNodeEndpointProperties endpoint : nodes) {
            BizAssert.notNull(endpoint, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
            BizAssert.hasText(endpoint.getCode(), ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
            BizAssert.notNull(endpoint.getRole(), ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
            BizAssert.isTrue(endpoint.getPriority() > 0, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
            BizAssert.isTrue(isRootHttpUrl(endpoint.getBaseUrl()), ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
            BizAssert.isTrue(nodeCodes.add(endpoint.getCode()), ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
            nodeRoles.add(endpoint.getRole());
        }

        BizAssert.isTrue(nodeRoles.contains(TronNodeRole.FULL_NODE) && nodeRoles.contains(TronNodeRole.SOLIDITY_NODE), ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }

    private boolean isRootHttpUrl(URI uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        String path = uri.getPath();
        return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
            && (path == null || path.isEmpty() || "/".equals(path))
            && uri.getQuery() == null
            && uri.getFragment() == null
            && uri.getUserInfo() == null;
    }
}
