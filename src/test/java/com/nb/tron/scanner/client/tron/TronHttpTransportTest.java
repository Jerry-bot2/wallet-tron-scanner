package com.nb.tron.scanner.client.tron;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.support.JsonCodec;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TRON HTTP 完整响应的超时与大小限制测试。
 * <p>
 * Author: bin jack
 * Date: 06.10.26
 */
class TronHttpTransportTest {

    private HttpServer server;
    private HttpClient httpClient;
    private ExecutorService serverExecutor;
    private ExecutorService requestExecutor;
    private TronScannerProperties properties;
    private TronHttpTransport transport;
    private TronNodeEndpointProperties endpoint;

    @BeforeEach
    void setUp() throws IOException {
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(serverExecutor);
        server.start();
        httpClient = HttpClient.newHttpClient();
        properties = new TronScannerProperties();
        properties.getNode().setReadTimeout(Duration.ofSeconds(1));
        transport = new TronHttpTransport(new JsonCodec(new ObjectMapper()), httpClient, properties);
        endpoint = new TronNodeEndpointProperties();
        endpoint.setBaseUrl(URI.create("http://localhost:" + server.getAddress().getPort()));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        serverExecutor.shutdownNow();
        httpClient.shutdownNow();
        requestExecutor.close();
    }

    @Test
    void shouldTimeoutWhenHeadersArriveButBodyNeverCompletes() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        installStalledResponse("/stalled", 200, 100, "{\"height\":", bodyStarted, releaseBody);
        server.createContext("/healthy", exchange -> respond(exchange, "{\"height\":100}"));

        Future<JsonNode> request = requestExecutor.submit(() -> transport.post(endpoint, "/stalled", "{}"));
        try {
            assertThat(bodyStarted.await(2, TimeUnit.SECONDS)).isTrue();
            // 节点始终不结束响应体；调用必须自己超时，不能依赖服务端断开连接。
            assertRequestFailure(request, ScannerBizErrCode.TRON_NODE_TIMEOUT);
            assertThat(transport.post(endpoint, "/healthy", "{}").path("height").longValue()).isEqualTo(100);
            // 不释放服务端响应体，客户端也应能结束这次交换，不留下仍在等待的请求。
            httpClient.shutdown();
            assertThat(httpClient.awaitTermination(Duration.ofSeconds(2))).isTrue();
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void shouldTimeoutEvenWhenBodyKeepsArrivingSlowly() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        server.createContext("/trickle", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write("{\"height\":".getBytes(StandardCharsets.UTF_8));
                body.flush();
                bodyStarted.countDown();
                // 每 100ms 传一个空格，始终有数据到达，但整个响应不能无限延期。
                while (!awaitRelease(releaseBody, 100, TimeUnit.MILLISECONDS)) {
                    body.write(' ');
                    body.flush();
                }
            }
        });

        Future<JsonNode> request = requestExecutor.submit(() -> transport.post(endpoint, "/trickle", "{}"));
        try {
            assertThat(bodyStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertRequestFailure(request, ScannerBizErrCode.TRON_NODE_TIMEOUT);
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void shouldTimeoutWhenResponseHeadersNeverArrive() throws Exception {
        CountDownLatch requestArrived = new CountDownLatch(1);
        CountDownLatch releaseResponse = new CountDownLatch(1);
        server.createContext("/no-headers", exchange -> {
            exchange.getRequestBody().readAllBytes();
            requestArrived.countDown();
            awaitRelease(releaseResponse, 10, TimeUnit.SECONDS);
            exchange.close();
        });

        Future<JsonNode> request = requestExecutor.submit(() -> transport.post(endpoint, "/no-headers", "{}"));
        try {
            assertThat(requestArrived.await(2, TimeUnit.SECONDS)).isTrue();
            assertRequestFailure(request, ScannerBizErrCode.TRON_NODE_TIMEOUT);
        } finally {
            releaseResponse.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 10_000})
    void shouldRejectOversizedBodyBeforeServerFinishes(long responseLength) throws Exception {
        properties.getNode().setReadTimeout(Duration.ofSeconds(10));
        properties.getNode().setMaxResponseSize(DataSize.ofBytes(128));
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        // 覆盖 chunked 和声明 Content-Length 两种响应；不能等收完才检查大小。
        installStalledResponse("/oversized", 200, responseLength, "a".repeat(256), bodyStarted, releaseBody);

        Future<JsonNode> request = requestExecutor.submit(() -> transport.post(endpoint, "/oversized", "{}"));
        try {
            assertThat(bodyStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertRequestFailure(request, ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
            httpClient.shutdown();
            assertThat(httpClient.awaitTermination(Duration.ofSeconds(2))).isTrue();
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void shouldAcceptCompleteBodyExactlyAtSizeLimit() {
        String response = "{\"height\":100}";
        properties.getNode().setMaxResponseSize(DataSize.ofBytes(response.getBytes(StandardCharsets.UTF_8).length));
        server.createContext("/complete", exchange -> respond(exchange, response));

        assertThat(transport.post(endpoint, "/complete", "{}").path("height").longValue()).isEqualTo(100);
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 503})
    void shouldRejectHttpErrorWithoutWaitingForBody(int statusCode) throws Exception {
        properties.getNode().setReadTimeout(Duration.ofSeconds(10));
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        installStalledResponse("/error", statusCode, 100, "{", bodyStarted, releaseBody);

        Future<JsonNode> request = requestExecutor.submit(() -> transport.post(endpoint, "/error", "{}"));
        try {
            assertThat(bodyStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertRequestFailure(request, statusCode == 429
                ? ScannerBizErrCode.TRON_NODE_RATE_LIMITED : ScannerBizErrCode.TRON_NODE_REMOTE_ERROR);
            httpClient.shutdown();
            assertThat(httpClient.awaitTermination(Duration.ofSeconds(2))).isTrue();
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void shouldCancelRequestAndPreserveThreadInterrupt() throws Exception {
        properties.getNode().setReadTimeout(Duration.ofSeconds(10));
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        installStalledResponse("/interrupt", 200, 100, "{", bodyStarted, releaseBody);
        AtomicReference<Thread> callingThread = new AtomicReference<>();
        Future<Boolean> interrupted = requestExecutor.submit(() -> {
            callingThread.set(Thread.currentThread());
            assertThatThrownBy(() -> transport.post(endpoint, "/interrupt", "{}"))
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getErrorCode())
                .isEqualTo(ScannerBizErrCode.TRON_NODE_CONNECT_FAILED);
            return Thread.currentThread().isInterrupted();
        });
        try {
            assertThat(bodyStarted.await(2, TimeUnit.SECONDS)).isTrue();
            callingThread.get().interrupt();
            assertThat(interrupted.get(3, TimeUnit.SECONDS)).isTrue();
            httpClient.shutdown();
            assertThat(httpClient.awaitTermination(Duration.ofSeconds(2))).isTrue();
        } finally {
            releaseBody.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-json"})
    void shouldRejectEmptyOrMalformedJson(String response) {
        server.createContext("/invalid", exchange -> respond(exchange, response));

        assertThatThrownBy(() -> transport.post(endpoint, "/invalid", "{}"))
            .isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
    }

    private void assertRequestFailure(Future<JsonNode> request, ScannerBizErrCode errorCode) {
        assertThatThrownBy(() -> request.get(3, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .cause().isInstanceOf(BizException.class)
            .extracting(exception -> ((BizException) exception).getErrorCode())
            .isEqualTo(errorCode);
    }

    private void installStalledResponse(String path, int statusCode, long responseLength, String partialBody,
                                        CountDownLatch bodyStarted, CountDownLatch releaseBody) {
        server.createContext(path, exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(statusCode, responseLength);
            try (var body = exchange.getResponseBody()) {
                try {
                    body.write(partialBody.getBytes(StandardCharsets.UTF_8));
                    body.flush();
                } finally {
                    // 错误状态可能使客户端立即断开；不能因此让测试一直等这个信号。
                    bodyStarted.countDown();
                }
                awaitRelease(releaseBody, 10, TimeUnit.SECONDS);
            }
        });
    }

    private void respond(HttpExchange exchange, String response) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private boolean awaitRelease(CountDownLatch release, long timeout, TimeUnit unit) {
        try {
            return release.await(timeout, unit);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return true;
        }
    }
}
