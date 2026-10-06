package com.nb.tron.scanner.client.tron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.support.JsonCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Author: bin jack
 * Date: 03.10.26
 */
class TronNodeClientTest {

    private HttpServer server;

    private TronNodeClient nodeClient;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();

        TronScannerProperties scannerProperties = new TronScannerProperties();
        TronHttpTransport httpTransport = new TronHttpTransport(
            new JsonCodec(new ObjectMapper()),
            HttpClient.newHttpClient(),
            scannerProperties);
        nodeClient = new TronNodeClient(httpTransport);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldReadRealNileGenesisWithOmittedZeroFields() throws IOException {
        String genesis;
        try (InputStream input = getClass().getResourceAsStream("/samples/tron/nile-genesis-block.json")) {
            assertThat(input).isNotNull();
            genesis = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        server.createContext("/wallet/getblockbynum", exchange -> respond(exchange, 200, genesis));

        TronNodeHeight block = nodeClient.getBlockHeaderByHeight(
            endpoint("nile-full", TronNodeRole.FULL_NODE, null), 0);

        assertThat(block.blockHeight()).isZero();
        assertThat(block.blockTimestamp()).isEqualTo(Instant.EPOCH);
        assertThat(block.blockId()).isEqualTo(
            "0000000000000000d698d4192c56cb6be724a558448e2684802de4d6cd8690dc");
    }

    @ParameterizedTest
    @ValueSource(strings = {"number", "timestamp"})
    void shouldRejectMissingHeaderFieldOutsideGenesis(String fieldName) throws IOException {
        var mapper = new ObjectMapper();
        var block = mapper.readTree(blockJson(100, "block-100", "block-99", ""));
        ((ObjectNode) block.path("block_header").path("raw_data"))
            .remove(fieldName);
        server.createContext("/wallet/getblockbynum", exchange -> respond(exchange, 200, block.toString()));

        assertThatThrownBy(() -> nodeClient.getBlockHeaderByHeight(
            endpoint("full-primary", TronNodeRole.FULL_NODE, null), 100))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
    }

    @ParameterizedTest
    @ValueSource(strings = {"number", "timestamp"})
    void shouldRejectExplicitNullHeaderFieldInGenesis(String fieldName) throws IOException {
        var mapper = new ObjectMapper();
        var block = mapper.readTree(blockJson(0, "block-0", "", ""));
        ((ObjectNode) block.path("block_header").path("raw_data"))
            .putNull(fieldName);
        server.createContext("/wallet/getblockbynum", exchange -> respond(exchange, 200, block.toString()));

        assertThatThrownBy(() -> nodeClient.getBlockHeaderByHeight(
            endpoint("full-primary", TronNodeRole.FULL_NODE, null), 0))
            .isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
    }

    @Test
    void shouldQueryHeadHeightAndSendApiKey() {
        AtomicReference<String> apiKey = new AtomicReference<>();
        server.createContext("/wallet/getnowblock", exchange -> {
            apiKey.set(exchange.getRequestHeaders().getFirst("TRON-PRO-API-KEY"));
            respond(exchange, 200, blockJson(100L, "block-100", "block-99", ""));
        });

        TronNodeHeight height = nodeClient.getHeadHeight(
            endpoint("full-primary", TronNodeRole.FULL_NODE, "test-key"));

        assertThat(height.nodeCode()).isEqualTo("full-primary");
        assertThat(height.blockHeight()).isEqualTo(100L);
        assertThat(height.blockId()).isEqualTo("block-100");
        assertThat(apiKey.get()).isEqualTo("test-key");
    }

    @Test
    void shouldCombineBlockTransactionsAndReceipts() {
        String transaction = """
            {"txID":"tx-1","raw_data":{"contract":[{"type":"TransferContract"}]}}
            """;
        String receipt = """
            {"id":"tx-1","blockNumber":100,"result":"SUCESS","log":[]}
            """;
        server.createContext("/wallet/getblockbynum", exchange ->
            respond(exchange, 200, blockJson(100L, "block-100", "block-99", transaction)));
        server.createContext("/wallet/gettransactioninfobyblocknum", exchange ->
            respond(exchange, 200, "[" + receipt + "]"));

        TronBlockData blockData = nodeClient.getBlockDataByHeight(
            endpoint("full-primary", TronNodeRole.FULL_NODE, null), 100L);

        assertThat(blockData.blockHeight()).isEqualTo(100L);
        assertThat(blockData.parentBlockId()).isEqualTo("block-99");
        assertThat(blockData.transactions()).hasSize(1);
        assertThat(blockData.transactions().getFirst().rawJson()).contains("TransferContract");
        assertThat(blockData.receipts().get("tx-1").rawJson()).contains("\"log\":[]");
    }

    @Test
    void shouldRejectBlockChangedWhileReadingReceiptsWithSameTransactionId() {
        AtomicReference<String> blockId = new AtomicReference<>("block-100-old");
        AtomicInteger blockReads = new AtomicInteger();
        String transaction = """
            {"txID":"tx-1","raw_data":{"contract":[{"type":"TransferContract"}]}}
            """;
        server.createContext("/wallet/getblockbynum", exchange -> {
            blockReads.incrementAndGet();
            respond(exchange, 200, blockJson(100L, blockId.get(), "block-99", transaction));
        });
        server.createContext("/wallet/gettransactioninfobyblocknum", exchange -> {
            // 同一交易在新区块中重新打包，交易 ID 和高度都相同，仅核对回执集合无法识别。
            blockId.set("block-100-new");
            respond(exchange, 200, "[{\"id\":\"tx-1\",\"blockNumber\":100,\"log\":[]}]");
        });

        assertThatThrownBy(() -> nodeClient.getBlockDataByHeight(
            endpoint("full-primary", TronNodeRole.FULL_NODE, null), 100L))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        assertThat(blockReads.get()).isEqualTo(2);
    }

    @Test
    void shouldRejectBlockWhenHashRecheckCannotBeCompleted() {
        AtomicInteger blockReads = new AtomicInteger();
        server.createContext("/wallet/getblockbynum", exchange -> {
            if (blockReads.incrementAndGet() == 1) {
                respond(exchange, 200, blockJson(100L, "block-100", "block-99", ""));
                return;
            }
            respond(exchange, 503, "{}");
        });
        server.createContext("/wallet/gettransactioninfobyblocknum", exchange -> respond(exchange, 200, "[]"));

        assertThatThrownBy(() -> nodeClient.getBlockDataByHeight(
            endpoint("full-primary", TronNodeRole.FULL_NODE, null), 100L))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_REMOTE_ERROR);
    }

    @Test
    void shouldRejectReceiptFromDifferentBlockHeight() {
        String transaction = """
            {"txID":"tx-1","raw_data":{"contract":[{"type":"TransferContract"}]}}
            """;
        String receipt = """
            {"id":"tx-1","blockNumber":99,"result":"SUCESS","log":[]}
            """;
        server.createContext("/wallet/getblockbynum", exchange ->
            respond(exchange, 200, blockJson(100L, "block-100", "block-99", transaction)));
        server.createContext("/wallet/gettransactioninfobyblocknum", exchange ->
            respond(exchange, 200, "[" + receipt + "]"));

        assertThatThrownBy(() -> nodeClient.getBlockDataByHeight(
            endpoint("full-primary", TronNodeRole.FULL_NODE, null), 100L))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
    }

    @Test
    void shouldRejectBlockWithUnexpectedHeight() {
        server.createContext("/wallet/getblockbynum", exchange ->
            respond(exchange, 200, blockJson(99L, "block-99", "block-98", "")));

        assertThatThrownBy(() -> nodeClient.getBlockDataByHeight(
            endpoint("full-primary", TronNodeRole.FULL_NODE, null), 100L))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
    }

    @Test
    void shouldLinkAdjacentBlocksByParentBlockId() {
        server.createContext("/wallet/getblockbynum", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (requestBody.contains("101")) {
                respond(exchange, 200, blockJson(101L, "block-101", "block-100", ""));
                return;
            }
            respond(exchange, 200, blockJson(100L, "block-100", "block-99", ""));
        });
        server.createContext("/wallet/gettransactioninfobyblocknum", exchange ->
            respond(exchange, 200, "[]"));
        TronNodeEndpointProperties fullNode = endpoint(
            "full-primary",
            TronNodeRole.FULL_NODE,
            null);

        TronBlockData block100 = nodeClient.getBlockDataByHeight(fullNode, 100L);
        TronBlockData block101 = nodeClient.getBlockDataByHeight(fullNode, 101L);

        assertThat(block101.parentBlockId()).isEqualTo(block100.blockId());
    }

    @Test
    void shouldRejectMissingBlockBeforeQueryingReceipts() {
        server.createContext("/wallet/getblockbynum", exchange -> respond(exchange, 200, "{}"));

        assertThatThrownBy(() -> nodeClient.getBlockDataByHeight(
            endpoint("full-primary", TronNodeRole.FULL_NODE, null), 100L))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_BLOCK_NOT_FOUND);
    }

    @Test
    void shouldClassifyRateLimitedResponse() {
        server.createContext("/wallet/getnowblock", exchange -> respond(exchange, 429, "{}"));

        assertThatThrownBy(() -> nodeClient.getHeadHeight(
            endpoint("full-primary", TronNodeRole.FULL_NODE, null)))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_RATE_LIMITED);
    }

    private TronNodeEndpointProperties endpoint(String code,
                                                TronNodeRole role,
                                                String apiKey) {
        TronNodeEndpointProperties endpoint = new TronNodeEndpointProperties();
        endpoint.setCode(code);
        endpoint.setRole(role);
        endpoint.setPriority(1);
        endpoint.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        endpoint.setApiKey(apiKey);
        return endpoint;
    }

    private String blockJson(long height,
                             String blockId,
                             String parentBlockId,
                             String transaction) {
        String transactions = transaction.isBlank() ? "[]" : "[" + transaction + "]";
        return """
            {
              "blockID":"%s",
              "block_header":{"raw_data":{
                "number":%d,
                "parentHash":"%s",
                "timestamp":1720000000000
              }},
              "transactions":%s
            }
            """.formatted(blockId, height, parentBlockId, transactions);
    }

    private void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
