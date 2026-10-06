package com.nb.tron.scanner.client.tron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.biz.HeadBlockAncestorFinder;
import com.nb.tron.scanner.biz.HeadBlockContinuityService;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.entity.TronScanCheckpoint;
import com.nb.tron.scanner.entity.TronScannedBlock;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.node.TronNodeHealthService;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.node.TronNodeStartupValidator;
import com.nb.tron.scanner.support.HeadScanTestDatabase;
import com.nb.tron.scanner.support.JsonCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TRON 真实测试网节点验收。
 *
 * <p>显式配置测试网地址和创世块 ID 后执行，普通构建自动跳过。
 * 公共测试网只读取数据；分叉场景向独立 H2 数据库注入旧分支 Hash，
 * 再使用真实节点查找共同区块、回退和重扫，不声称测试网发生真实分叉。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
class TronNodeTestnetAcceptanceTest {

    private TronScannerProperties properties;
    private TronNodeClient nodeClient;
    private TronNodeEndpointProperties fullNode;
    private String expectedGenesisBlockId;

    @BeforeEach
    void setUp() {
        String fullNodeUrl = System.getenv("TRON_FULL_NODE_PRIMARY_URL");
        expectedGenesisBlockId = System.getenv("TRON_GENESIS_BLOCK_ID");
        assumeTrue(hasText(fullNodeUrl) && hasText(expectedGenesisBlockId),
            "未配置 TRON 测试网节点，跳过真实节点验收");

        String network = System.getenv("TRON_TEST_NETWORK");
        network = hasText(network) ? network : "NILE";
        assertThat(network).as("真实验收只使用测试网").isIn("NILE", "SHASTA");
        properties = new TronScannerProperties();
        properties.setChainNetwork(network);
        properties.setExpectedGenesisBlockId(expectedGenesisBlockId);
        nodeClient = new TronNodeClient(new TronHttpTransport(
            new JsonCodec(new ObjectMapper()),
            HttpClient.newBuilder().connectTimeout(properties.getNode().getConnectTimeout()).build(),
            properties));
        fullNode = endpoint("full-acceptance", TronNodeRole.FULL_NODE, fullNodeUrl,
            System.getenv("TRON_FULL_NODE_PRIMARY_API_KEY"));
        properties.getNode().setNodes(List.of(fullNode));
    }

    @Test
    void shouldStartWithFullNodeOnlyAndReadConsecutiveBlocks() {
        TronNodeManager manager = validatedManager();
        TronNodeHeight head = manager.getHeadHeight();
        long height = head.blockHeight() - 100;
        assertThat(height).isPositive();

        TronBlockData block = manager.getBlockDataByHeight(height);
        TronBlockData nextBlock = manager.getBlockDataByHeight(height + 1);
        assertThat(nextBlock.parentBlockId()).isEqualTo(block.blockId());
        assertThat(block.receipts().keySet()).containsExactlyInAnyOrderElementsOf(
            block.transactions().stream().map(transaction -> transaction.transactionId()).toList());
        assertThat(nextBlock.receipts()).hasSize(nextBlock.transactions().size());
        System.out.printf("TESTNET_READ network=%s host=%s head=%d blocks=%d..%d transactions=%d/%d%n",
            properties.getChainNetwork(), fullNode.getBaseUrl().getHost(), head.blockHeight(),
            height, height + 1, block.transactions().size(), nextBlock.transactions().size());
    }

    @Test
    void shouldReadConsistentBlockFromOptionalSolidityNode() {
        String solidityUrl = System.getenv("TRON_SOLIDITY_NODE_URL");
        assumeTrue(hasText(solidityUrl), "未配置可选固化节点，跳过两类节点对比");
        TronNodeEndpointProperties solidityNode = endpoint("solidity-acceptance", TronNodeRole.SOLIDITY_NODE,
            solidityUrl, System.getenv("TRON_SOLIDITY_NODE_API_KEY"));
        assertThat(nodeClient.getBlockHeaderByHeight(solidityNode, 0).blockId())
            .isEqualToIgnoringCase(expectedGenesisBlockId);

        TronNodeHeight solidHeight = nodeClient.getSolidHeight(solidityNode);
        TronNodeHeight headHeight = nodeClient.getHeadHeight(fullNode);
        assertThat(headHeight.blockHeight()).isGreaterThanOrEqualTo(solidHeight.blockHeight());
        TronBlockData fullBlock = nodeClient.getBlockDataByHeight(fullNode, solidHeight.blockHeight());
        assertThat(fullBlock.blockId()).isEqualToIgnoringCase(solidHeight.blockId());
        System.out.printf("TESTNET_SOLID_COMPARE network=%s solid=%d blockId=%s%n",
            properties.getChainNetwork(), solidHeight.blockHeight(), fullBlock.blockId());
    }

    @Test
    void shouldFindRewindAndReplayInjectedOldBranchesUsingRealTestnetBlocks() throws Exception {
        TronNodeManager manager = validatedManager();
        long lastHeight = manager.getHeadHeight().blockHeight() - 100;
        long firstHeight = lastHeight - 8;
        properties.setStartBlockHeight(firstHeight + 1);
        Map<Long, TronNodeHeight> headers = new LinkedHashMap<>();
        for (long height = firstHeight; height <= lastHeight; height++) {
            headers.put(height, manager.getBlockHeaderByHeight(height));
        }

        // 三种深度共用同一段真实历史，仅本地保留的分叉部分 Hash 被替换。
        for (int forkDepth : List.of(1, 4, 8)) {
            long commonHeight = lastHeight - forkDepth;
            try (HeadScanTestDatabase database = new HeadScanTestDatabase()) {
                for (TronNodeHeight header : headers.values()) {
                    String storedHash = header.blockHeight() <= commonHeight
                        ? header.blockId() : injectedOldHash(header.blockId());
                    database.blocks().saveBlock(new TronScannedBlock().setChainNetwork(properties.getChainNetwork())
                        .setBlockNumber(header.blockHeight()).setBlockHash(storedHash));
                }
                TronScanCheckpoint oldCheckpoint = new TronScanCheckpoint()
                    .setChainNetwork(properties.getChainNetwork()).setLastBlockNumber(lastHeight)
                    .setLastBlockHash(injectedOldHash(headers.get(lastHeight).blockId()));
                database.checkpoints().save(oldCheckpoint);
                var progress = database.progress(properties, manager);
                var finder = new HeadBlockAncestorFinder(manager, database.blocks());
                var continuity = new HeadBlockContinuityService(finder, progress, manager);

                assertThatThrownBy(() -> continuity.checkCheckpoint(progress.loadCheckpoint()))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ScannerBizErrCode.HEAD_SCAN_FORK_DETECTED);
                assertThat(database.checkpoints().findByNetwork(properties.getChainNetwork()).getLastBlockNumber())
                    .isEqualTo(commonHeight);
                assertThat(database.blocks().findByHeight(properties.getChainNetwork(), commonHeight + 1)).isNull();

                // 重新创建进度服务模拟重启，用真实区块和回执逐块重扫。
                var restartedProgress = database.progress(properties, manager);
                TronScanCheckpoint checkpoint = restartedProgress.loadCheckpoint();
                for (long height = commonHeight + 1; height <= lastHeight; height++) {
                    TronBlockData block = manager.getBlockDataByHeight(height);
                    continuity.checkNextBlock(checkpoint, block);
                    checkpoint = restartedProgress.advance(checkpoint, block);
                }
                assertThat(checkpoint.getLastBlockNumber()).isEqualTo(lastHeight);
                assertThat(checkpoint.getLastBlockHash()).isEqualTo(headers.get(lastHeight).blockId());
                assertThat(database.blocks().findOldestBlock(properties.getChainNetwork()).getBlockNumber())
                    .isEqualTo(firstHeight);
                System.out.printf("TESTNET_INJECTED_FORK network=%s depth=%d common=%d replayedTo=%d%n",
                    properties.getChainNetwork(), forkDepth, commonHeight, lastHeight);
            }
        }
    }

    private TronNodeManager validatedManager() {
        TronNodeHealthService health = new TronNodeHealthService(properties, nodeClient);
        new TronNodeStartupValidator(health).validateConfiguredNodes();
        return new TronNodeManager(properties, health, nodeClient);
    }

    private String injectedOldHash(String blockId) {
        char last = blockId.charAt(blockId.length() - 1);
        return blockId.substring(0, blockId.length() - 1) + (last == '0' ? '1' : '0');
    }

    private TronNodeEndpointProperties endpoint(String code, TronNodeRole role, String baseUrl, String apiKey) {
        TronNodeEndpointProperties endpoint = new TronNodeEndpointProperties();
        endpoint.setCode(code);
        endpoint.setRole(role);
        endpoint.setPriority(1);
        endpoint.setBaseUrl(URI.create(baseUrl));
        endpoint.setApiKey(apiKey);
        return endpoint;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
