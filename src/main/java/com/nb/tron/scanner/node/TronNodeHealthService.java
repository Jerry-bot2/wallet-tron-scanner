package com.nb.tron.scanner.node;

import com.nb.core.exception.BizException;
import com.nb.tron.scanner.client.tron.TronNodeClient;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.model.TronNodeRuntimeState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.nb.tron.scanner.constant.TronConstants.GENESIS_BLOCK_HEIGHT;

/**
 * TRON 节点健康服务: 定时维护每个节点的健康状态和节点高度
 *
 * <p>逐个探测配置节点，并在当前 Scanner 实例的内存中维护最新运行状态。</p>
 *
 * <p>节点状态变化规则：</p>
 * <ol>
 *     <li>检查成功：节点标记为 {@code HEALTHY}，连续失败次数清零；</li>
 *     <li>偶发失败：累加失败次数，达到阈值前保留节点原来的健康状态，避免网络抖动导致节点频繁上下线；</li>
 *     <li>连续失败：失败次数达到 {@code failureThreshold} 后，节点标记为 {@code UNHEALTHY}；</li>
 *     <li>故障恢复：不可用节点仍会继续接受检查，后续检查成功时重新标记为 {@code HEALTHY}。</li>
 * </ol>
 *
 * <p>单次业务请求失败后的备用节点切换由 {@code TronNodeManager} 负责，
 * 本服务只判断节点的持续运行状态。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TronNodeHealthService {

    private final TronScannerProperties scannerProperties;

    private final TronNodeClient nodeClient;

    /**
     * 保存所有节点状态的内存 Map
     * full-primary → 节点运行状态
     * full-backup  → 节点运行状态
     */
    private final ConcurrentMap<String, TronNodeRuntimeState> nodeStates = new ConcurrentHashMap<>();

    /**
     * 刷新全部节点状态。
     *
     * <p>应用启动时由启动校验器执行一次，运行期间由本机定时任务重复执行。</p>
     *
     * @return 本次刷新完成后的节点状态
     */
    @Scheduled(
        initialDelayString = "${nb.tron.scanner.node.health-check-interval:15s}",
        fixedDelayString = "${nb.tron.scanner.node.health-check-interval:15s}")
    public List<TronNodeRuntimeState> refreshNodeStates() {
        scannerProperties.getNode().getNodes().forEach(this::refreshNodeState);
        return getNodeStates();
    }

    /**
     * 返回当前全部节点状态快照。
     */
    public List<TronNodeRuntimeState> getNodeStates() {
        return scannerProperties.getNode().getNodes().stream()
            .map(TronNodeEndpointProperties::getCode)
            .map(nodeStates::get)
            .filter(Objects::nonNull)
            .toList();
    }

    /**
     * 按节点编码查询当前状态。
     */
    public Optional<TronNodeRuntimeState> findNodeState(String nodeCode) {
        return Optional.ofNullable(nodeStates.get(nodeCode));
    }

    private void refreshNodeState(TronNodeEndpointProperties endpoint) {
        long startedAt = System.nanoTime();
        try {
            TronNodeHeight nodeHeight = probeNode(endpoint);
            long responseTimeMillis = elapsedMillis(startedAt);
            nodeStates.compute(endpoint.getCode(), (nodeCode, currentState) -> TronNodeRuntimeState.success(
                endpoint.getCode(),
                endpoint.getRole(),
                currentState,
                nodeHeight.blockHeight(),
                responseTimeMillis,
                Instant.now()));
            log.debug("TRON节点健康检查通过，nodeCode={}，role={}，blockHeight={}，responseTimeMillis={}",
                endpoint.getCode(),
                endpoint.getRole(),
                nodeHeight.blockHeight(),
                responseTimeMillis);
        } catch (BizException exception) {
            recordFailure(endpoint, startedAt, exception.getCode());
        } catch (RuntimeException exception) {
            recordFailure(endpoint, startedAt, ScannerBizErrCode.TRON_NODE_REMOTE_ERROR.getCode());
            log.warn("TRON节点健康检查发生未预期异常，nodeCode={}，role={}",
                endpoint.getCode(),
                endpoint.getRole(),
                exception);
        }
    }

    private TronNodeHeight probeNode(TronNodeEndpointProperties endpoint) {
        validateNetwork(endpoint);
        if (endpoint.getRole() == TronNodeRole.FULL_NODE) {
            return nodeClient.getHeadHeight(endpoint);
        }
        return nodeClient.getSolidHeight(endpoint);
    }

    private void validateNetwork(TronNodeEndpointProperties endpoint) {
        TronNodeHeight genesisBlock = nodeClient.getBlockHeaderByHeight(endpoint, GENESIS_BLOCK_HEIGHT);
        if (!scannerProperties.getExpectedGenesisBlockId().equalsIgnoreCase(genesisBlock.blockId())) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_NETWORK_MISMATCH);
        }
    }

    /**
     * 记录当前节点的一次检查失败。
     *
     * <p>失败次数在多轮定时检查之间持续累加，达到配置阈值后才将节点标记为不可用。</p>
     */
    private void recordFailure(TronNodeEndpointProperties endpoint, long startedAt, Integer errorCode) {
        long responseTimeMillis = elapsedMillis(startedAt);
        TronNodeRuntimeState state = nodeStates.compute(
            endpoint.getCode(),
            (nodeCode, currentState) -> TronNodeRuntimeState.failure(
                endpoint.getCode(),
                endpoint.getRole(),
                currentState,
                responseTimeMillis,
                scannerProperties.getNode().getFailureThreshold()));

        log.warn("TRON节点健康检查失败，nodeCode={}，role={}，failureCount={}，healthStatus={}，errorCode={}",
            endpoint.getCode(),
            endpoint.getRole(),
            state.consecutiveFailureCount(),
            state.healthStatus(),
            errorCode);
    }

    private long elapsedMillis(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
    }
}
