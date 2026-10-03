package com.nb.tron.scanner.client.tron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.support.JsonCodec;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TRON 真实测试网节点验收。
 *
 * <p>只有提供测试网节点环境变量时才会执行，普通本地构建会自动跳过。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
class TronNodeTestnetAcceptanceTest {

    @Test
    void shouldReadConsistentBlockFromConfiguredTestnetNodes() {
        String fullNodeUrl = System.getenv("TRON_FULL_NODE_PRIMARY_URL");
        String solidityNodeUrl = System.getenv("TRON_SOLIDITY_NODE_URL");
        String expectedGenesisBlockId = System.getenv("TRON_GENESIS_BLOCK_ID");
        assumeTrue(hasText(fullNodeUrl)
                && hasText(solidityNodeUrl)
                && hasText(expectedGenesisBlockId),
            "未配置 TRON 测试网节点，跳过真实节点验收");

        TronScannerProperties scannerProperties = new TronScannerProperties();
        TronNodeClient nodeClient = new TronNodeClient(new TronHttpTransport(
            new JsonCodec(new ObjectMapper()),
            HttpClient.newHttpClient(),
            scannerProperties));
        TronNodeEndpointProperties fullNode = endpoint(
            "full-acceptance",
            TronNodeRole.FULL_NODE,
            fullNodeUrl,
            System.getenv("TRON_FULL_NODE_PRIMARY_API_KEY"));
        TronNodeEndpointProperties solidityNode = endpoint(
            "solidity-acceptance",
            TronNodeRole.SOLIDITY_NODE,
            solidityNodeUrl,
            System.getenv("TRON_SOLIDITY_NODE_API_KEY"));

        TronNodeHeight fullGenesis = nodeClient.getBlockHeaderByHeight(fullNode, 0L);
        TronNodeHeight solidGenesis = nodeClient.getBlockHeaderByHeight(solidityNode, 0L);
        assertThat(fullGenesis.blockId()).isEqualToIgnoringCase(expectedGenesisBlockId);
        assertThat(solidGenesis.blockId()).isEqualToIgnoringCase(expectedGenesisBlockId);

        TronNodeHeight headHeight = nodeClient.getHeadHeight(fullNode);
        TronNodeHeight solidHeight = nodeClient.getSolidHeight(solidityNode);
        assertThat(headHeight.blockHeight()).isGreaterThanOrEqualTo(solidHeight.blockHeight());

        TronBlockData fullBlock = nodeClient.getBlockDataByHeight(
            fullNode,
            solidHeight.blockHeight());
        TronNodeHeight solidBlock = nodeClient.getBlockHeaderByHeight(
            solidityNode,
            solidHeight.blockHeight());
        assertThat(fullBlock.blockId()).isEqualToIgnoringCase(solidBlock.blockId());
        assertThat(fullBlock.blockHeight()).isEqualTo(solidHeight.blockHeight());
    }

    private TronNodeEndpointProperties endpoint(String code,
                                                TronNodeRole role,
                                                String baseUrl,
                                                String apiKey) {
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
