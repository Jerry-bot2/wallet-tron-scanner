package com.nb.tron.scanner.client.tron;

import com.fasterxml.jackson.databind.JsonNode;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.support.HeadScanStatistics;
import com.nb.tron.scanner.support.JsonCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.nb.tron.scanner.constant.TronHttpConstants.API_KEY_HEADER;

/**
 * TRON 节点 HTTP 传输客户端。
 *
 * <p>每次只执行一次请求，不做业务重试和节点切换。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
@RequiredArgsConstructor
class TronHttpTransport {

    private final JsonCodec jsonCodec;

    private final HttpClient httpClient;

    private final TronScannerProperties scannerProperties;

    /**
     * 向指定节点发送一次 JSON POST 请求。
     *
     * @param endpoint    目标节点
     * @param path        固定节点接口路径
     * @param requestBody JSON 请求体
     * @return 校验通过的 JSON 响应
     */
    JsonNode post(TronNodeEndpointProperties endpoint, String path, String requestBody) {
        return HeadScanStatistics.timeNodeRead(() -> executePost(endpoint, path, requestBody));
    }

    private JsonNode executePost(TronNodeEndpointProperties endpoint, String path, String requestBody) {
        HttpRequest request = buildRequest(endpoint, path, requestBody);
        HttpResponse<byte[]> response = send(request);
        return parseResponse(response.body());
    }

    private HttpRequest buildRequest(TronNodeEndpointProperties endpoint,
                                     String path,
                                     String requestBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint.getBaseUrl().resolve(path))
            .timeout(scannerProperties.getNode().getReadTimeout())
            .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        if (StringUtils.hasText(endpoint.getApiKey())) {
            builder.header(API_KEY_HEADER, endpoint.getApiKey());
        }
        return builder.build();
    }

    /**
     * 1. 接收响应时限制大小，超过上限立即取消读取。
     * 2. 等整个响应体收完才返回；响应头先到、正文一直不结束，也受 readTimeout 限制。
     * 3. 超时或线程中断时取消 HTTP 请求，让扫块线程结束等待。
     */
    private HttpResponse<byte[]> send(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> responseFuture = httpClient.sendAsync(request, responseInfo -> {
            validateStatus(responseInfo.statusCode());
            return new LimitedResponseBodySubscriber(scannerProperties.getNode().getMaxResponseSize().toBytes());
        });
        try {
            return responseFuture.get(request.timeout().orElseThrow().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            responseFuture.cancel(true);
            throw new BizException(ScannerBizErrCode.TRON_NODE_TIMEOUT, exception);
        } catch (InterruptedException exception) {
            responseFuture.cancel(true);
            Thread.currentThread().interrupt();
            throw new BizException(ScannerBizErrCode.TRON_NODE_CONNECT_FAILED, exception);
        } catch (ExecutionException exception) {
            throw responseFailure(exception.getCause());
        }
    }

    private BizException responseFailure(Throwable cause) {
        if (cause instanceof BizException exception) {
            return exception;
        }
        ScannerBizErrCode errorCode = cause instanceof HttpTimeoutException
            ? ScannerBizErrCode.TRON_NODE_TIMEOUT
            : ScannerBizErrCode.TRON_NODE_CONNECT_FAILED;
        return new BizException(errorCode, cause);
    }

    private void validateStatus(int statusCode) {
        // 请求太频繁，被节点限流了
        if (statusCode == 429) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RATE_LIMITED);
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_REMOTE_ERROR);
        }
    }

    private JsonNode parseResponse(byte[] responseBody) {
        if (responseBody.length == 0) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        }
        try {
            JsonNode response = jsonCodec.readTree(responseBody);
            if (response == null) {
                throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
            }
            if (response.isObject() && (response.has("Error") || response.has("error"))) {
                throw BizException.of(ScannerBizErrCode.TRON_NODE_REMOTE_ERROR);
            }
            return response;
        } catch (UncheckedIOException exception) {
            throw new BizException(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID, exception);
        }
    }
}
