package com.nb.tron.scanner.node;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.sdk.enums.TronNodeRole;
import com.nb.tron.sdk.node.TronNodePool;
import com.nb.tron.sdk.node.TronNodeState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * TRON 节点启动校验器
 *
 * <p>应用启动时逐个检查节点网络和读取能力，避免 Scanner 使用错误网络或不可用节点。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TronNodeStartupValidator implements ApplicationRunner {

    private final TronNodePool nodePool;

    @Override
    public void run(ApplicationArguments args) {
        validateConfiguredNodes();
    }

    /**
     * 校验当前环境配置的全部 TRON 节点。
     *
     * <p>
     * 1.网络检查：读取节点的创世区块，将区块 ID 与当前环境配置进行比较，
     * 防止生产环境连接到测试网络；
     * 2.可用性检查：FullNode 查询最新 Head 高度，SolidityNode 查询最新固化高度，
     * 确认节点能够正常返回合法区块信息；
     * 3.启动判定：至少一个 FullNode 可用才允许 Scanner 启动。
     * SolidityNode 为可选读取能力，不参与 Scanner 扫块和分叉恢复。
     * </p>
     */
    public void validateConfiguredNodes() {
        nodePool.refresh();
        List<TronNodeState> nodeStates = nodePool.states();
        long fullNodeCount = countHealthyNodes(nodeStates, TronNodeRole.FULL_NODE);
        long solidityNodeCount = countHealthyNodes(nodeStates, TronNodeRole.SOLIDITY_NODE);

        validateStartupReadiness(fullNodeCount);

        log.info("TRON节点启动校验完成，availableFullNodes={}，availableSolidityNodes={}",
            fullNodeCount,
            solidityNodeCount);
    }

    private long countHealthyNodes(List<TronNodeState> nodeStates, TronNodeRole role) {
        return nodeStates.stream()
            .filter(TronNodeState::isHealthy)
            .filter(state -> state.nodeRole() == role)
            .count();
    }

    private void validateStartupReadiness(long fullNodeCount) {
        if (fullNodeCount == 0) {
            throw BizException.of(ScannerBizErrCode.TRON_SDK_CALL_FAILED);
        }
    }
}
