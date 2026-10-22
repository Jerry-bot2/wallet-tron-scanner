package com.nb.tron.scanner.client.tron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.node.TronNodeStartupValidator;
import com.nb.tron.sdk.block.TronBlockGateway;
import com.nb.tron.sdk.client.TronHttpTransport;
import com.nb.tron.sdk.client.TronNodeClient;
import com.nb.tron.sdk.codec.JsonCodec;
import com.nb.tron.sdk.enums.TronNodeRole;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronNodeHeight;
import com.nb.tron.sdk.node.TronNodePool;
import com.nb.tron.sdk.node.TronNodePoolOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TRON 真实测试网节点验收。
 *
 * <p>显式配置测试网地址和创世块 ID 后执行，普通构建自动跳过。
 * 公共测试网只读取数据，验证FullNode读取结果与SolidityNode固化边界保持一致。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
class TronNodeTestnetAcceptanceTest {

    private TronScannerProperties properties;
    private TronNodeClient nodeClient;
    private TronNodeEndpointProperties fullNode;
    private TronNodeEndpointProperties solidityNode;
    private String expectedGenesisBlockId;

    @BeforeEach
    void setUp() {
        String fullNodeUrl = System.getenv("TRON_FULL_NODE_PRIMARY_URL");
        String solidityNodeUrl = System.getenv("TRON_SOLIDITY_NODE_URL");
        expectedGenesisBlockId = System.getenv("TRON_GENESIS_BLOCK_ID");
        assumeTrue(hasText(fullNodeUrl) && hasText(solidityNodeUrl) && hasText(expectedGenesisBlockId),
            "未同时配置TRON测试网FullNode、SolidityNode和创世块，跳过真实节点验收");

        String network = System.getenv("TRON_TEST_NETWORK");
        network = hasText(network) ? network : "NILE";
        assertThat(network).as("真实验收只使用测试网").isIn("NILE", "SHASTA");
        properties = new TronScannerProperties();
        properties.setChainNetwork(network);
        properties.setExpectedGenesisBlockId(expectedGenesisBlockId);
        nodeClient = new TronNodeClient(new TronHttpTransport(HttpClient.newBuilder().connectTimeout(properties.getNode().getConnectTimeout()).build(), new JsonCodec(new ObjectMapper()), properties.getNode().getReadTimeout(), properties.getNode().getMaxResponseSize().toBytes()));
        fullNode = endpoint("full-acceptance", TronNodeRole.FULL_NODE, fullNodeUrl,
            System.getenv("TRON_FULL_NODE_PRIMARY_API_KEY"));
        solidityNode = endpoint("solidity-acceptance", TronNodeRole.SOLIDITY_NODE, solidityNodeUrl,
            System.getenv("TRON_SOLIDITY_NODE_API_KEY"));
        properties.getNode().setNodes(List.of(fullNode, solidityNode));
    }

    @Test
    void shouldStartWithRequiredNodesAndReadConsecutiveSolidifiedBlocks() {
        TronNodeManager manager = validatedManager();
        TronNodeHeight head = manager.getHeadHeight();
        TronNodeHeight solid = manager.getSolidHeight();
        long height = solid.blockHeight() - 100;
        assertThat(height).isPositive();
        assertThat(head.blockHeight()).isGreaterThanOrEqualTo(solid.blockHeight());

        TronBlockData block = manager.getBlockDataByHeight(height);
        TronBlockData nextBlock = manager.getBlockDataByHeight(height + 1);
        assertThat(nextBlock.parentBlockId()).isEqualTo(block.blockId());
        assertThat(block.receipts().keySet()).containsExactlyInAnyOrderElementsOf(
            block.transactions().stream().map(transaction -> transaction.transactionId()).toList());
        assertThat(nextBlock.receipts()).hasSize(nextBlock.transactions().size());
        System.out.printf("TESTNET_READ network=%s host=%s head=%d solid=%d blocks=%d..%d transactions=%d/%d%n",
            properties.getChainNetwork(), fullNode.getBaseUrl().getHost(), head.blockHeight(), solid.blockHeight(),
            height, height + 1, block.transactions().size(), nextBlock.transactions().size());
    }

    @Test
    void shouldReadSameSolidifiedBlockFromBothNodeRoles() {
        assertThat(nodeClient.getBlockHeaderByHeight(solidityNode.toSdkEndpoint(), 0).blockId())
            .isEqualToIgnoringCase(expectedGenesisBlockId);

        TronNodeHeight solidHeight = nodeClient.getSolidHeight(solidityNode.toSdkEndpoint());
        TronNodeHeight headHeight = nodeClient.getHeadHeight(fullNode.toSdkEndpoint());
        assertThat(headHeight.blockHeight()).isGreaterThanOrEqualTo(solidHeight.blockHeight());
        TronBlockData fullBlock = nodeClient.getBlockDataByHeight(fullNode.toSdkEndpoint(), solidHeight.blockHeight());
        assertThat(fullBlock.blockId()).isEqualToIgnoringCase(solidHeight.blockId());
        System.out.printf("TESTNET_SOLID_COMPARE network=%s solid=%d blockId=%s%n",
            properties.getChainNetwork(), solidHeight.blockHeight(), fullBlock.blockId());
    }

    private TronNodeManager validatedManager() {
        TronNodePool nodePool = new TronNodePool(
            properties.getNode().getNodes().stream()
                .map(TronNodeEndpointProperties::toSdkDefinition)
                .toList(),
            new TronNodePoolOptions(
                properties.getExpectedGenesisBlockId(),
                properties.getNode().getFailureThreshold(),
                properties.getNode().getHeightLagThreshold(),
                properties.getNode().getRecoveryCooldown()),
            nodeClient);
        new TronNodeStartupValidator(nodePool).validateConfiguredNodes();
        return new TronNodeManager(new TronBlockGateway(nodePool, nodeClient, 2));
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
