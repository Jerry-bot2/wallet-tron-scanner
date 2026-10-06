package com.nb.tron.scanner.client.tron;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nb.chain.client.enums.AddressPurpose;
import com.nb.chain.client.event.ObservedBlockEvent;
import com.nb.core.exception.BizException;
import com.nb.kafka.core.KafkaPublishResult;
import com.nb.kafka.core.KafkaPublisher;
import com.nb.tron.scanner.biz.HeadBlockAncestorFinder;
import com.nb.tron.scanner.biz.HeadBlockScanService;
import com.nb.tron.scanner.biz.HeadBlockContinuityService;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.enums.TronTokenStandard;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.job.HeadBlockScanJob;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import com.nb.tron.scanner.model.TronNodeRuntimeState;
import com.nb.tron.scanner.mq.publisher.DepositDiscoveryPublisher;
import com.nb.tron.scanner.node.TronNodeHealthService;
import com.nb.tron.scanner.node.TronNodeManager;
import com.nb.tron.scanner.parser.Trc20TransferParser;
import com.nb.tron.scanner.parser.TronBlockParser;
import com.nb.tron.scanner.parser.TrxTransferParser;
import com.nb.tron.scanner.support.HeadScanTestDatabase;
import com.nb.tron.scanner.support.JsonCodec;
import com.nb.tron.scanner.support.TronAddressCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentNavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 充值发现与分叉恢复的整条 Scanner 流程验收。
 *
 * <p>使用可切换分支的本地 HTTP 节点、真实解析器、MyBatis 和手动事务。
 * Kafka 的 ACK 使用受控 Future，验证发送、失败、回退及重启后的实际数据库结果。</p>
 * <p>分支由测试注入，不代表公共测试网发生了真实分叉。</p>
 * <p>
 * Author: bin jack
 * Date: 05.10.26
 */
@ExtendWith(OutputCaptureExtension.class)
class HeadBlockScanAcceptanceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentNavigableMap<Long, SampleBlock> chain = new ConcurrentSkipListMap<>();
    private final List<ObservedBlockEvent> sentEvents = new ArrayList<>();

    private HttpServer server;
    private HeadScanTestDatabase database;
    private TronScannerProperties properties;
    private TronNodeEndpointProperties endpoint;
    private TronNodeClient nodeClient;
    private TronNodeHealthService healthService;
    private TronAddressIndex addressIndex;
    private TronCurrencyIndex currencyIndex;
    private TronBlockParser parser;
    private DepositDiscoveryPublisher publisher;
    private TestScanJob job;
    private ObjectNode blockSample;
    private ArrayNode receiptSample;
    private RuntimeException nextPublishFailure;
    private Runnable afterAck = () -> { };
    private Runnable afterReceipt = () -> { };

    @BeforeEach
    void setUp() throws Exception {
        blockSample = (ObjectNode) readSample("/samples/tron/block-100.json");
        receiptSample = (ArrayNode) readSample("/samples/tron/transaction-info-100.json");
        for (long height = 99; height <= 106; height++) {
            int deposits = height == 100 ? 1 : height == 101 ? 2 : 0;
            chain.put(height, sample(height, "h" + height, "h" + (height - 1), deposits));
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/wallet/getblock", exchange -> {
            JsonNode request = objectMapper.readTree(exchange.getRequestBody());
            long height = request.has("id_or_num")
                ? Long.parseLong(request.path("id_or_num").textValue()) : chain.lastKey();
            SampleBlock block = chain.get(height);
            ObjectNode header = block == null ? objectMapper.createObjectNode() : block.block().deepCopy();
            header.remove("transactions");
            respond(exchange, header);
        });
        server.createContext("/wallet/getblockbynum", exchange -> {
            long height = requestHeight(exchange);
            SampleBlock block = chain.get(height);
            respond(exchange, block == null ? objectMapper.createObjectNode() : block.block());
        });
        server.createContext("/wallet/gettransactioninfobyblocknum", exchange -> {
            SampleBlock block = chain.get(requestHeight(exchange));
            JsonNode receipts = block == null ? objectMapper.createArrayNode() : block.receipts();
            afterReceipt.run();
            respond(exchange, receipts);
        });
        server.start();

        properties = new TronScannerProperties();
        properties.setStartBlockHeight(100L);
        endpoint = new TronNodeEndpointProperties();
        endpoint.setCode("sample-full-node");
        endpoint.setRole(TronNodeRole.FULL_NODE);
        endpoint.setPriority(1);
        endpoint.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        properties.getNode().setNodes(List.of(endpoint));
        JsonCodec jsonCodec = new JsonCodec(objectMapper);
        TronAddressCodec addressCodec = new TronAddressCodec();
        nodeClient = new TronNodeClient(new TronHttpTransport(jsonCodec, HttpClient.newHttpClient(), properties));
        healthService = mock(TronNodeHealthService.class);
        when(healthService.getNodeStates()).thenReturn(List.of(TronNodeRuntimeState.success(
            endpoint.getCode(), TronNodeRole.FULL_NODE, null, 106, 1, Instant.now().minusSeconds(120))));

        addressIndex = mock(TronAddressIndex.class);
        when(addressIndex.isReady()).thenReturn(true);
        when(addressIndex.findPurpose(addressCodec.fromHex("411111111111111111111111111111111111111111")))
            .thenReturn(AddressPurpose.DEPOSIT);
        currencyIndex = new TronCurrencyIndex();
        currencyIndex.replaceAll(List.of(
            new TronCurrencyConfig("TRX", TronTokenStandard.NATIVE, "", 6),
            new TronCurrencyConfig("USDT", TronTokenStandard.TRC20, "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t", 6)));
        parser = new TronBlockParser(
            new TrxTransferParser(jsonCodec, addressCodec, addressIndex, currencyIndex, properties),
            new Trc20TransferParser(jsonCodec, addressCodec, addressIndex, currencyIndex, properties));
        KafkaPublisher kafka = mock(KafkaPublisher.class);
        when(kafka.publish(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ObservedBlockEvent event = invocation.getArgument(2);
            sentEvents.add(event);
            // 发送时数据库仍停在前一块，必须等 ACK 成功后才能提交进度。
            assertThat(database.checkpoints().findByNetwork("MAINNET").getLastBlockNumber())
                .isEqualTo(event.getBlockNumber() - 1);
            if (nextPublishFailure != null) {
                RuntimeException failure = nextPublishFailure;
                nextPublishFailure = null;
                return CompletableFuture.failedFuture(failure);
            }
            afterAck.run();
            return CompletableFuture.completedFuture(new KafkaPublishResult(invocation.getArgument(0), 0, 1, 0));
        });
        publisher = new DepositDiscoveryPublisher(properties, kafka);
        database = new HeadScanTestDatabase();
        restartScanner();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        if (database != null) {
            database.close();
        }
    }

    @Test
    void shouldScanEmptySingleAndMultipleDepositBlocksThenResumeWithoutResending(CapturedOutput output) {
        assertThat(job.runRound()).isEqualTo(7);
        assertThat(sentEvents).hasSize(2);
        assertThat(sentEvents.get(0).getDeposits()).hasSize(1);
        assertThat(sentEvents.get(1).getDeposits()).hasSize(2);
        assertThat(sentEvents.get(1).getDeposits()).extracting(event -> event.getCurrency())
            .containsExactly("TRX", "USDT");
        assertThat(sentEvents.get(1).getDeposits().get(1).getRawAmount()).isEqualTo(BigInteger.valueOf(1_000_000));
        assertCheckpoint(106, "h106");
        assertThat(output).contains("completed=true，forkRecheck=false，startHeight=99，lastCompletedHeight=106，observedHeadHeight=106，remainingBlocks=0，scannedCount=7");
        assertThat(output).contains("nodeReadMillis=", "kafkaAckMillis=", "progressCommitMillis=");
        assertThat(output.getOut().lines().filter(line -> line.contains("TRON Head扫描本轮结束")).count()).isEqualTo(1L);

        restartScanner();
        assertThat(job.runRound()).isZero();
        assertThat(sentEvents).hasSize(2);
        assertThat(output).contains("startHeight=106，lastCompletedHeight=106，observedHeadHeight=106，remainingBlocks=0，scannedCount=0");
        assertThat(output).contains("averageBlockMillis=null");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, 5, 7})
    void shouldRewindDifferentForkDepthsAndReplayAfterRestart(int forkDepth, CapturedOutput output) {
        job.runRound();
        long commonHeight = 106 - forkDepth;
        forkFrom(commonHeight, "new");
        int messagesBeforeRewind = sentEvents.size();

        assertThat(job.runRound()).isZero();
        assertCheckpoint(commonHeight, "h" + commonHeight);
        assertThat(output).contains("completed=true，forkRecheck=true，startHeight=106，lastCompletedHeight=106，observedHeadHeight=null，remainingBlocks=null，scannedCount=0");
        assertThat(database.blocks().findByHeight("MAINNET", commonHeight + 1)).isNull();
        assertThat(sentEvents).hasSize(messagesBeforeRewind);

        restartScanner();
        assertThat(job.runRound()).isEqualTo(forkDepth);
        assertCheckpoint(106, "new106");
        for (long height = commonHeight + 1; height <= 106; height++) {
            assertThat(database.blocks().findByHeight("MAINNET", height).getBlockHash()).isEqualTo("new" + height);
        }
        // 保留同一交易 ID 的重新打包样本：重新发送时携带新 Hash，供链服务固化确认。
        for (ObservedBlockEvent event : sentEvents.subList(messagesBeforeRewind, sentEvents.size())) {
            assertThat(event.getBlockHash()).startsWith("new");
        }
    }

    @Test
    void shouldDetectForkDuringSameRoundBeforeParsingTheNextBlock(CapturedOutput output) {
        afterAck = () -> {
            forkFrom(99, "new");
            afterAck = () -> { };
        };

        // 100 的 ACK 已收到，随后 100 被替换；读 101 时父 Hash 接不上刚保存的旧 100。
        assertThat(job.runRound()).isZero();
        assertCheckpoint(99, "h99");
        assertThat(database.blocks().findByHeight("MAINNET", 100)).isNull();
        assertThat(sentEvents).hasSize(1);
        assertThat(output).contains("completed=true，forkRecheck=true，startHeight=99，lastCompletedHeight=100，observedHeadHeight=106，remainingBlocks=null，scannedCount=1");

        restartScanner();
        assertThat(job.runRound()).isEqualTo(7);
        assertCheckpoint(106, "new106");
        assertThat(sentEvents).hasSize(3);
    }

    @Test
    void shouldRecoverFromTwoSuccessiveForks() {
        job.runRound();
        forkFrom(103, "branch-b-");
        assertThat(job.runRound()).isZero();
        assertThat(job.runRound()).isEqualTo(3);
        assertCheckpoint(106, "branch-b-106");

        // 第二次分叉的共同区块属于第一次重扫的新分支。
        forkFrom(104, "branch-c-");
        assertThat(job.runRound()).isZero();
        assertCheckpoint(104, "branch-b-104");
        restartScanner();
        assertThat(job.runRound()).isEqualTo(2);
        assertCheckpoint(106, "branch-c-106");
        assertThat(database.blocks().findByHeight("MAINNET", 104).getBlockHash()).isEqualTo("branch-b-104");
    }

    @Test
    void shouldRecoverAtRecentWindowBoundary() {
        properties.setBlockHistorySize(4);
        prepareCleanupBoundarySamples();
        job.runRound();
        assertThat(database.blocks().findByHeight("MAINNET", 196)).isNull();
        forkFrom(197, "new");

        assertThat(job.runRound()).isZero();
        assertCheckpoint(197, "h197");
        assertThat(job.runRound()).isEqualTo(3);
        assertCheckpoint(200, "new200");
    }

    @Test
    void shouldReportDeepForkAndPreserveProgressAfterRestart() {
        properties.setBlockHistorySize(4);
        prepareCleanupBoundarySamples();
        job.runRound();
        forkFrom(196, "new");
        int messagesBeforeFork = sentEvents.size();

        // 保留 197～200，共同区块 196 已被清理：不能直接跳过新分支，也不从配置起点重扫。
        assertThat(database.blocks().findByHeight("MAINNET", 196)).isNull();
        assertThatThrownBy(job::runRound).isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_COMMON_ANCESTOR_NOT_FOUND);
        assertCheckpoint(200, "h200");
        assertThat(database.blocks().findByHeight("MAINNET", 200).getBlockHash()).isEqualTo("h200");
        assertThat(sentEvents).hasSize(messagesBeforeFork);

        restartScanner();
        assertThatThrownBy(job::runRound).isInstanceOf(BizException.class);
        assertCheckpoint(200, "h200");
        assertThat(sentEvents).hasSize(messagesBeforeFork);
    }

    @Test
    void shouldNotCommitMixedBranchDataWhenBlockChangesDuringReceiptRead() {
        afterReceipt = () -> {
            forkFrom(99, "new");
            afterReceipt = () -> { };
        };

        assertThatThrownBy(job::runRound).isInstanceOf(BizException.class);
        assertCheckpoint(99, "h99");
        assertThat(database.blocks().findByHeight("MAINNET", 100)).isNull();
        assertThat(sentEvents).isEmpty();

        restartScanner();
        assertThat(job.runRound()).isEqualTo(7);
        assertCheckpoint(106, "new106");
    }

    @Test
    void shouldKeepSuccessfulCommitCountWhenLaterPublishFails(CapturedOutput output) {
        afterAck = () -> {
            nextPublishFailure = new IllegalStateException("第二个区块 ACK 失败");
            afterAck = () -> { };
        };

        assertThatThrownBy(job::runRound).isInstanceOf(BizException.class);
        assertCheckpoint(100, "h100");
        assertThat(output).contains("completed=false，forkRecheck=false，startHeight=99，lastCompletedHeight=100，observedHeadHeight=106，remainingBlocks=6，scannedCount=1");

        restartScanner();
        assertThat(job.runRound()).isEqualTo(6);
        assertCheckpoint(106, "h106");
        assertThat(output).contains("completed=true，forkRecheck=false，startHeight=100，lastCompletedHeight=106，observedHeadHeight=106，remainingBlocks=0，scannedCount=6");
    }

    @Test
    void shouldResendSameDepositAfterKafkaFailureAndRestart(CapturedOutput output) {
        nextPublishFailure = new IllegalStateException("模拟 Broker ACK 失败");

        assertThatThrownBy(job::runRound).isInstanceOf(BizException.class)
            .extracting(e -> ((BizException) e).getErrorCode())
            .isEqualTo(ScannerBizErrCode.HEAD_SCAN_KAFKA_PUBLISH_FAILED);
        assertThat(output).contains("completed=false，forkRecheck=false，startHeight=99，lastCompletedHeight=99，observedHeadHeight=106，remainingBlocks=7，scannedCount=0");
        assertCheckpoint(99, "h99");
        assertThat(database.blocks().findByHeight("MAINNET", 100)).isNull();

        restartScanner();
        assertThat(job.runRound()).isEqualTo(7);
        assertCheckpoint(106, "h106");
        assertThat(sentEvents).hasSize(3);
        assertThat(sentEvents.get(0)).isEqualTo(sentEvents.get(1));
        assertThat(output).contains("completed=true，forkRecheck=false，startHeight=99，lastCompletedHeight=106，observedHeadHeight=106，remainingBlocks=0，scannedCount=7");
    }

    /**
     * 从 194 扫到 200，经过一次真实清理；194、195 带充值，其余为空块。
     */
    private void prepareCleanupBoundarySamples() {
        chain.clear();
        for (long height = 193; height <= 200; height++) {
            int deposits = height == 194 ? 1 : height == 195 ? 2 : 0;
            chain.put(height, sample(height, "h" + height, "h" + (height - 1), deposits));
        }
        properties.setStartBlockHeight(194L);
        when(healthService.getNodeStates()).thenReturn(List.of(TronNodeRuntimeState.success(
            endpoint.getCode(), TronNodeRole.FULL_NODE, null, 200, 1, Instant.now().minusSeconds(120))));
        restartScanner();
    }

    private void restartScanner() {
        var manager = new TronNodeManager(properties, healthService, nodeClient);
        var progress = database.progress(properties, manager);
        var finder = new HeadBlockAncestorFinder(manager, database.blocks());
        var continuity = new HeadBlockContinuityService(manager, finder, progress);
        job = new TestScanJob(new HeadBlockScanService(properties, addressIndex, currencyIndex,
            manager, parser, publisher, continuity, progress));
    }

    private void assertCheckpoint(long height, String hash) {
        var checkpoint = database.checkpoints().findByNetwork("MAINNET");
        assertThat(checkpoint.getLastBlockNumber()).isEqualTo(height);
        assertThat(checkpoint.getLastBlockHash()).isEqualTo(hash);
    }

    /**
     * 保留共同区块之前的分支，替换之后的 Hash 和父 Hash，交易 ID 保持不变。
     */
    private void forkFrom(long commonHeight, String prefix) {
        String parentHash = chain.get(commonHeight).block().path("blockID").textValue();
        for (long height = commonHeight + 1; height <= chain.lastKey(); height++) {
            SampleBlock old = chain.get(height);
            ObjectNode block = old.block().deepCopy();
            String hash = prefix + height;
            block.put("blockID", hash);
            ((ObjectNode) block.path("block_header").path("raw_data")).put("parentHash", parentHash);
            chain.put(height, new SampleBlock(block, old.receipts()));
            parentHash = hash;
        }
    }

    private SampleBlock sample(long height, String hash, String parentHash, int depositCount) {
        ObjectNode block = blockSample.deepCopy();
        block.put("blockID", hash);
        ObjectNode header = (ObjectNode) block.path("block_header").path("raw_data");
        header.put("number", height);
        header.put("parentHash", parentHash);
        ArrayNode transactions = objectMapper.createArrayNode();
        ArrayNode receipts = objectMapper.createArrayNode();
        for (int index = 0; index < depositCount; index++) {
            ObjectNode transaction = ((ObjectNode) blockSample.path("transactions").get(index)).deepCopy();
            String txId = transaction.path("txID").textValue() + "-" + height;
            transaction.put("txID", txId);
            transactions.add(transaction);
            ObjectNode receipt = ((ObjectNode) receiptSample.get(index)).deepCopy();
            receipt.put("id", txId);
            receipt.put("blockNumber", height);
            receipts.add(receipt);
        }
        block.set("transactions", transactions);
        return new SampleBlock(block, receipts);
    }

    private JsonNode readSample(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).isNotNull();
            return objectMapper.readTree(input);
        }
    }

    private long requestHeight(HttpExchange exchange) throws IOException {
        return objectMapper.readTree(exchange.getRequestBody()).path("num").longValue();
    }

    private void respond(HttpExchange exchange, JsonNode body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static class TestScanJob extends HeadBlockScanJob {

        private TestScanJob(HeadBlockScanService service) {
            super(service);
        }

        private int runRound() {
            return super.doExecute(null);
        }
    }

    private record SampleBlock(ObjectNode block, ArrayNode receipts) {
    }
}
