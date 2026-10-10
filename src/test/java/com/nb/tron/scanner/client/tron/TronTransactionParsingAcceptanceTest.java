package com.nb.tron.scanner.client.tron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nb.chain.client.enums.AddressPurpose;
import com.nb.tron.scanner.biz.DepositDiscoveryService;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.sdk.client.TronNodeClient;
import com.nb.tron.sdk.codec.TronAddressCodec;
import com.nb.tron.sdk.enums.TronNodeRole;
import com.nb.tron.sdk.enums.TronTokenStandard;
import com.nb.tron.sdk.model.TronBlockData;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * TRON 交易解析链路样本验收测试。
 *
 * <p>使用接近 FullNode 返回结构的固定样本，串联节点响应转换、区块编排、
 * TRX 解析和 TRC20 解析，确认整个阶段 5 的边界能够正确协作。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
class TronTransactionParsingAcceptanceTest {

    private static final long BLOCK_HEIGHT = 100L;

    private static final String DEPOSIT_ADDRESS_HEX =
        "411111111111111111111111111111111111111111";

    private static final String USDT_CONTRACT_ADDRESS =
        "TR7NHqjeKQxGTCi8q8ZY4pL8otSzgjLj6t";

    private HttpServer server;

    private TronNodeClient nodeClient;

    private DepositDiscoveryService blockParser;

    @BeforeEach
    void setUp() throws IOException {
        String blockSample = readSample("/samples/tron/block-100.json");
        String receiptSample = readSample("/samples/tron/transaction-info-100.json");
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/wallet/getblockbynum", exchange -> respond(exchange, blockSample));
        ObjectNode headerSample = (ObjectNode) new ObjectMapper().readTree(blockSample);
        headerSample.remove("transactions");
        server.createContext("/wallet/getblock", exchange -> respond(exchange, headerSample.toString()));
        server.createContext(
            "/wallet/gettransactioninfobyblocknum",
            exchange -> respond(exchange, receiptSample));
        server.start();

        TronScannerProperties scannerProperties = new TronScannerProperties();
        ObjectMapper objectMapper = new ObjectMapper();
        TronAddressCodec addressCodec = new TronAddressCodec();
        nodeClient = new TronNodeClient(HttpClient.newHttpClient(), objectMapper,
            scannerProperties.getNode().getReadTimeout(), scannerProperties.getNode().getMaxResponseSize().toBytes());

        TronAddressIndex addressIndex = mock(TronAddressIndex.class);
        when(addressIndex.findPurpose(addressCodec.fromHex(DEPOSIT_ADDRESS_HEX)))
            .thenReturn(AddressPurpose.DEPOSIT);
        TronCurrencyIndex currencyIndex = supportedCurrencies();
        blockParser = new DepositDiscoveryService(new com.nb.tron.sdk.parser.TronBlockParser(objectMapper), addressIndex, currencyIndex);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void shouldParseOnlySupportedDepositsFromBlockSample() {
        TronBlockData blockData = nodeClient.getBlockDataByHeight(fullNode().toSdkEndpoint(), BLOCK_HEIGHT);

        List<TronDepositEvent> events = blockParser.discover(blockData);

        assertThat(blockData.transactions()).hasSize(5);
        assertThat(events).hasSize(2);
        assertTrxDeposit(events.get(0));
        assertUsdtDeposit(events.get(1));
        assertThat(blockParser.discover(blockData)).isEqualTo(events);
    }

    private void assertTrxDeposit(TronDepositEvent event) {
        assertThat(event.currency()).isEqualTo("TRX");
        assertThat(event.txId()).isEqualTo("tx-trx-deposit");
        assertThat(event.eventIndex()).isEqualTo(-1);
        assertThat(event.rawAmount()).isEqualTo(BigInteger.valueOf(2_000_000L));
        assertThat(event.blockNumber()).isEqualTo(BLOCK_HEIGHT);
    }

    private void assertUsdtDeposit(TronDepositEvent event) {
        assertThat(event.currency()).isEqualTo("USDT");
        assertThat(event.contractAddress()).isEqualTo(USDT_CONTRACT_ADDRESS);
        assertThat(event.txId()).isEqualTo("tx-usdt-deposit");
        assertThat(event.eventIndex()).isZero();
        assertThat(event.rawAmount()).isEqualTo(BigInteger.valueOf(1_000_000L));
        assertThat(event.blockNumber()).isEqualTo(BLOCK_HEIGHT);
    }

    private TronCurrencyIndex supportedCurrencies() {
        TronCurrencyIndex currencyIndex = new TronCurrencyIndex();
        currencyIndex.replaceAll(List.of(
            new TronCurrencyConfig("TRX", TronTokenStandard.NATIVE, "", 6),
            new TronCurrencyConfig(
                "USDT",
                TronTokenStandard.TRC20,
                USDT_CONTRACT_ADDRESS,
                6)));
        return currencyIndex;
    }

    private TronNodeEndpointProperties fullNode() {
        TronNodeEndpointProperties endpoint = new TronNodeEndpointProperties();
        endpoint.setCode("sample-full-node");
        endpoint.setRole(TronNodeRole.FULL_NODE);
        endpoint.setPriority(1);
        endpoint.setBaseUrl(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
        return endpoint;
    }

    private String readSample(String path) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("Missing test sample: " + path);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] response = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
