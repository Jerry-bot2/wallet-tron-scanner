package com.nb.tron.scanner.node;

import com.nb.core.exception.BizAssert;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.client.tron.TronNodeClient;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.model.TronNodeRuntimeState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * TRON 节点选择与切换管理器
 *
 * <p>节点选择规则：</p>
 * <ol>
 *     <li>FullNode 和 SolidityNode 分开选择；</li>
 *     <li>首次按配置优先级选择节点，正常期间持续使用当前节点；</li>
 *     <li>读取指定高度时，只选择已经同步到该高度的 FullNode；</li>
 *     <li>当前节点不可用或明显落后时切换到合格的备用节点；</li>
 *     <li>高优先级节点恢复后，经过稳定观察时间才允许切回。</li>
 * </ol>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TronNodeManager {

    /**
     * 节点优先级数值越小，越优先使用；优先级相同时按节点编码固定顺序。
     */
    private static final Comparator<NodeCandidate> NODE_PRIORITY_COMPARATOR = Comparator
        .comparingInt((NodeCandidate candidate) -> candidate.endpoint().getPriority())
        .thenComparing(candidate -> candidate.endpoint().getCode());

    private final TronScannerProperties scannerProperties;

    private final TronNodeHealthService nodeHealthService;

    private final TronNodeClient nodeClient;

    /**
     * FullNode 和 SolidityNode 当前正在使用的节点编码。
     */
    private final ConcurrentMap<TronNodeRole, String> activeNodeCodes = new ConcurrentHashMap<>();

    /**
     * 请求失败或数据不一致的节点在冷却结束前不再参与选择，防止 A、B 节点反复切换。
     * 例如 A 请求失败后切到 B，接下来 60 秒继续使用 B，之后才允许再次选择 A。
     */
    private final ConcurrentMap<String, Instant> recoveryBlockedUntil = new ConcurrentHashMap<>();

    /**
     * 查询最新 Head 高度，业务代码不需要关心本次使用哪个 FullNode。
     * 查询成功后同步更新实际响应节点的内存高度，避免旧健康快照挡住本轮扫块。
     */
    public TronNodeHeight getHeadHeight() {
        TronNodeEndpointProperties selectedNode = selectFullNodeForHead();
        return executeReadWithFailover(
            selectedNode,
            this::readHeadHeight,
            this::switchFullNodeForHead);
    }

    /**
     * 读取最新高度并更新实际响应节点的快照。
     * 例如快照为 1000、本次读到 1003：先回写 1003，后续读取 1001 就不会被旧高度挡住。
     */
    private TronNodeHeight readHeadHeight(TronNodeEndpointProperties endpoint) {
        long readStartedAt = System.nanoTime();
        TronNodeHeight height = nodeClient.getHeadHeight(endpoint);
        nodeHealthService.updateLatestHeight(height, readStartedAt);
        return height;
    }

    /**
     * 查询最新固化高度，业务代码不需要关心本次使用哪个 SolidityNode。
     */
    public TronNodeHeight getSolidHeight() {
        TronNodeEndpointProperties selectedNode = selectSolidityNode();
        return executeReadWithFailover(
            selectedNode,
            nodeClient::getSolidHeight,
            this::switchSolidityNode);
    }

    /**
     * 按高度读取完整区块和交易回执。
     * 区块与回执始终在同一个 FullNode 上完成读取，节点失败时整体切换一次。
     */
    public TronBlockData getBlockDataByHeight(long blockHeight) {
        TronNodeEndpointProperties selectedNode = selectFullNodeForBlock(blockHeight);
        return executeReadWithFailover(
            selectedNode,
            endpoint -> nodeClient.getBlockDataByHeight(endpoint, blockHeight),
            failedNodeCode -> switchFullNodeForBlock(failedNodeCode, blockHeight));
    }

    /**
     * 按高度读取区块头。
     *
     * <p>每轮核对已扫描末块是否被替换，只读取区块头，不解析交易和回执。</p>
     */
    public TronNodeHeight getBlockHeaderByHeight(long blockHeight) {
        TronNodeEndpointProperties selectedNode = selectFullNodeForBlock(blockHeight);
        return executeReadWithFailover(
            selectedNode,
            endpoint -> nodeClient.getBlockHeaderByHeight(endpoint, blockHeight),
            failedNodeCode -> switchFullNodeForBlock(failedNodeCode, blockHeight));
    }

    /**
     * 为一次共同区块查找固定 FullNode，节点必须已经同步到扫描进度。
     * 后续请求失败时结束查找，下一轮从头使用备用节点，查找途中不切换节点。
     */
    public TronBlockHeaderReader openBlockHeaderReader(long requiredBlockHeight) {
        TronNodeEndpointProperties endpoint = selectFullNodeForBlock(requiredBlockHeight);
        return new TronBlockHeaderReader(nodeClient, endpoint, () -> startRecoveryCooldown(endpoint.getCode()));
    }

    /**
     * 将读取失败或返回不一致数据的节点暂时排除，冷却期间不再选择它。
     * 例如 A 的下一块接不上，但末块仍正确：冷却 A，下轮可选择 B 重新读取。
     * 找到共同区块后的正常回退不需要冷却节点。
     *
     * @param nodeCode 实际返回异常数据的节点编码
     */
    public void startRecoveryCooldown(String nodeCode) {
        recoveryBlockedUntil.put(
            nodeCode,
            Instant.now().plus(scannerProperties.getNode().getRecoveryCooldown()));
    }

    /**
     * 为查询最新 Head 高度选择一个健康且没有明显落后的 FullNode。
     */
    TronNodeEndpointProperties selectFullNodeForHead() {
        return selectNode(TronNodeRole.FULL_NODE);
    }

    /**
     * 为读取指定高度的完整区块选择 FullNode。
     * 例如下一块扫描 1001，只会选择节点高度大于或等于 1001 的节点。
     */
    TronNodeEndpointProperties selectFullNodeForBlock(long requiredBlockHeight) {
        BizAssert.isTrue(requiredBlockHeight >= 0, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
        return selectNode(TronNodeRole.FULL_NODE, requiredBlockHeight, null);
    }

    /**
     * 当前 FullNode 高度查询失败后选择备用节点。
     */
    TronNodeEndpointProperties switchFullNodeForHead(String failedNodeCode) {
        BizAssert.hasText(failedNodeCode, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
        startRecoveryCooldown(failedNodeCode);
        return selectNode(TronNodeRole.FULL_NODE, null, failedNodeCode);
    }

    /**
     * 当前 FullNode 区块读取失败后，选择一个符合高度要求的备用节点。
     */
    TronNodeEndpointProperties switchFullNodeForBlock(String failedNodeCode, long requiredBlockHeight) {
        BizAssert.hasText(failedNodeCode, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
        BizAssert.isTrue(requiredBlockHeight >= 0, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
        startRecoveryCooldown(failedNodeCode);
        return selectNode(TronNodeRole.FULL_NODE, requiredBlockHeight, failedNodeCode);
    }

    /**
     * 选择当前可用的 SolidityNode。
     */
    TronNodeEndpointProperties selectSolidityNode() {
        return selectNode(TronNodeRole.SOLIDITY_NODE);
    }

    /**
     * 当前 SolidityNode 请求失败后选择备用节点。
     */
    TronNodeEndpointProperties switchSolidityNode(String failedNodeCode) {
        BizAssert.hasText(failedNodeCode, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
        startRecoveryCooldown(failedNodeCode);
        return selectNode(TronNodeRole.SOLIDITY_NODE, null, failedNodeCode);
    }

    /**
     * 使用选中的节点读取数据，节点故障时只切换一个备用节点重试一次。
     *
     * <ol>
     *     <li>先使用当前选中的节点读取数据；</li>
     *     <li>当前节点发生可切换异常时，将它放入冷却期并选择备用节点；</li>
     *     <li>使用备用节点重新读取一次；</li>
     *     <li>备用节点仍然失败时结束调用，由下一轮扫描任务再次处理。</li>
     * </ol>
     */
    private <T> T executeReadWithFailover(
        TronNodeEndpointProperties selectedNode,
        Function<TronNodeEndpointProperties, T> readOperation,
        Function<String, TronNodeEndpointProperties> fallbackSelector) {
        try {
            return readOperation.apply(selectedNode);
        } catch (BizException exception) {
            TronNodeEndpointProperties fallbackNode = fallbackSelector.apply(selectedNode.getCode());
            log.warn("TRON节点读取失败，切换备用节点重试，failedNodeCode={}，fallbackNodeCode={}，errorCode={}",
                selectedNode.getCode(),
                fallbackNode.getCode(),
                exception.getCode());
            return readFromFallbackNode(fallbackNode, readOperation);
        }
    }

    private <T> T readFromFallbackNode(TronNodeEndpointProperties fallbackNode,
                                       Function<TronNodeEndpointProperties, T> readOperation) {
        try {
            return readOperation.apply(fallbackNode);
        } catch (BizException exception) {
            startRecoveryCooldown(fallbackNode.getCode());
            throw exception;
        }
    }

    private TronNodeEndpointProperties selectNode(TronNodeRole nodeRole) {
        return selectNode(nodeRole, null, null);
    }

    /**
     * 选择本次请求使用的节点。
     *
     * <ol>
     *     <li>找出角色正确、状态健康、高度足够的节点，并按优先级排序；</li>
     *     <li>当前节点仍可用时继续使用，只有原主节点稳定恢复后才切回；</li>
     *     <li>当前节点不可用时，改用候选列表中的第一个节点。</li>
     * </ol>
     *
     * <p>主节点 A 与备用节点 B 的完整切换过程：</p>
     * <ol>
     *     <li>正常工作：候选节点为 [A, B]，当前节点和首选节点都是 A，继续使用 A；</li>
     *     <li>A 请求失败：候选节点只剩 [B]，找不到可继续使用的当前节点，切换到 B；</li>
     *     <li>A 仍在冷却期：候选节点仍是 [B]，当前节点和首选节点都是 B，继续使用 B；</li>
     *     <li>A 稳定恢复：候选节点变为 [A, B]，当前节点是 B，首选节点是 A，切回 A。</li>
     * </ol>
     *
     * @param nodeRole            节点类型
     * @param requiredBlockHeight 本次请求需要读取的最低区块高度；不限制时为空
     * @param excludedNodeCode    本次请求明确排除的失败节点；没有时为空
     */
    private TronNodeEndpointProperties selectNode(TronNodeRole nodeRole,
                                                  Long requiredBlockHeight,
                                                  String excludedNodeCode) {
        Instant now = Instant.now();
        List<NodeCandidate> candidates = findAvailableNodes(
            nodeRole,
            requiredBlockHeight,
            excludedNodeCode,
            now);
        if (candidates.isEmpty()) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_UNAVAILABLE);
        }

        NodeCandidate activeCandidate = findActiveCandidate(nodeRole, candidates);
        NodeCandidate preferredCandidate = candidates.getFirst();

        // 1. 首次选择，或者当前节点已经不可用：改用当前最优的可用节点。
        if (activeCandidate == null) {
            return useNode(nodeRole, preferredCandidate);
        }

        // 2. 当前正在使用备用节点，原主节点已稳定恢复：切回优先级更高的主节点。
        if (shouldSwitchBackToPreferredNode(activeCandidate, preferredCandidate, now)) {
            return useNode(nodeRole, preferredCandidate);
        }

        // 3. 当前节点仍然适合使用：保持不变。
        return activeCandidate.endpoint();
    }

    /**
     * 从所有配置节点中找出本次请求可以使用的节点，本方法只生成候选列表，不负责切换节点。
     *
     * <ol>
     *     <li>读取当前环境配置的全部节点；</li>
     *     <li>根据节点编码关联健康检查产生的运行状态；</li>
     *     <li>排除角色不符合本次请求的节点；</li>
     *     <li>排除本次请求失败和仍在冷却期的节点；</li>
     *     <li>排除不健康或者尚未取得区块高度的节点；</li>
     *     <li>排除无法读取目标高度或者明显落后的 FullNode；</li>
     *     <li>按照配置优先级排序并返回，列表第一个就是首选节点。</li>
     * </ol>
     *
     * @param nodeRole            本次请求需要的节点角色
     * @param requiredBlockHeight 本次请求需要读取的最低区块高度；不限制时为空
     * @param excludedNodeCode    本次请求已经失败、不能再次选择的节点编码；没有时为空
     * @param now                 当前时间，用于判断节点是否仍在冷却期
     * @return 已按优先级排序的可用节点列表
     */
    private List<NodeCandidate> findAvailableNodes(TronNodeRole nodeRole,
                                                   Long requiredBlockHeight,
                                                   String excludedNodeCode,
                                                   Instant now) {
        Map<String, TronNodeRuntimeState> stateByNodeCode = nodeHealthService.getNodeStates().stream()
            .collect(Collectors.toMap(TronNodeRuntimeState::nodeCode, Function.identity()));

        List<NodeCandidate> healthyNodes = scannerProperties.getNode().getNodes().stream()
            .filter(endpoint -> endpoint.getRole() == nodeRole)
            .filter(endpoint -> !endpoint.getCode().equals(excludedNodeCode))
            .filter(endpoint -> !isInRecoveryCooldown(endpoint.getCode(), now))
            .map(endpoint -> new NodeCandidate(endpoint, stateByNodeCode.get(endpoint.getCode())))
            .filter(NodeCandidate::isHealthy)
            .toList();

        long highestFullNodeHeight = findHighestFullNodeHeight(healthyNodes);
        return healthyNodes.stream()
            .filter(candidate -> supportsHeight(candidate, requiredBlockHeight))
            .filter(candidate -> isNotLagging(candidate, highestFullNodeHeight))
            .sorted(NODE_PRIORITY_COMPARATOR)
            .toList();
    }

    private long findHighestFullNodeHeight(List<NodeCandidate> candidates) {
        return candidates.stream()
            .filter(candidate -> candidate.endpoint().getRole() == TronNodeRole.FULL_NODE)
            .map(candidate -> candidate.state().latestBlockHeight())
            .filter(Objects::nonNull)
            .mapToLong(Long::longValue)
            .max()
            .orElse(-1L);
    }

    private boolean supportsHeight(NodeCandidate candidate, Long requiredBlockHeight) {
        return requiredBlockHeight == null
            || candidate.state().latestBlockHeight() >= requiredBlockHeight;
    }

    private boolean isNotLagging(NodeCandidate candidate, long highestFullNodeHeight) {
        if (candidate.endpoint().getRole() != TronNodeRole.FULL_NODE || highestFullNodeHeight < 0) {
            return true;
        }
        long heightLag = highestFullNodeHeight - candidate.state().latestBlockHeight();
        return heightLag <= scannerProperties.getNode().getHeightLagThreshold();
    }

    /**
     * 从可用候选列表中找到当前正在使用的节点。
     * 当前节点尚未选择或者已经被候选列表排除时返回空。
     */
    private NodeCandidate findActiveCandidate(TronNodeRole nodeRole, List<NodeCandidate> candidates) {
        String activeNodeCode = activeNodeCodes.get(nodeRole);
        if (activeNodeCode == null) {
            return null;
        }
        return candidates.stream()
            .filter(candidate -> activeNodeCode.equals(candidate.endpoint().getCode()))
            .findFirst()
            .orElse(null);
    }

    /**
     * 备用节点正在工作时，只有更高优先级节点已连续健康满冷却时间才切回。
     */
    private boolean shouldSwitchBackToPreferredNode(NodeCandidate currentNode,
                                                    NodeCandidate preferredNode,
                                                    Instant now) {
        if (preferredNode.endpoint().getPriority() >= currentNode.endpoint().getPriority()) {
            return false;
        }
        Instant healthySince = preferredNode.state().healthySince();
        return healthySince != null
            && !healthySince.plus(scannerProperties.getNode().getRecoveryCooldown()).isAfter(now);
    }

    /**
     * 完成节点选择状态的切换
     */
    private TronNodeEndpointProperties useNode(TronNodeRole nodeRole, NodeCandidate candidate) {
        //切换前: FULL_NODE -> full-primary
        //切换后: FULL_NODE -> full-backup
        String previousNodeCode = activeNodeCodes.put(nodeRole, candidate.endpoint().getCode());
        if (!candidate.endpoint().getCode().equals(previousNodeCode)) {
            log.info("TRON节点已切换，role={}，previousNodeCode={}，currentNodeCode={}",
                nodeRole,
                previousNodeCode,
                candidate.endpoint().getCode());
        }
        return candidate.endpoint();
    }

    private boolean isInRecoveryCooldown(String nodeCode, Instant now) {
        Instant blockedUntil = recoveryBlockedUntil.get(nodeCode);
        if (blockedUntil == null) {
            return false;
        }
        if (blockedUntil.isAfter(now)) {
            return true;
        }
        recoveryBlockedUntil.remove(nodeCode, blockedUntil);
        return false;
    }

    private record NodeCandidate(TronNodeEndpointProperties endpoint, TronNodeRuntimeState state) {

        private boolean isHealthy() {
            return state != null && state.isHealthy() && state.latestBlockHeight() != null;
        }
    }
}
