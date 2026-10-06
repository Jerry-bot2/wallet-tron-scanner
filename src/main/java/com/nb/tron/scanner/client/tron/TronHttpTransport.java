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

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;

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
        HttpResponse<InputStream> response = send(request);
        validateStatus(response);

        byte[] responseBody = readResponseBody(response.body());
        return parseResponse(responseBody);
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

    private HttpResponse<InputStream> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException exception) {
            throw new BizException(ScannerBizErrCode.TRON_NODE_TIMEOUT, exception);
        } catch (IOException exception) {
            throw new BizException(ScannerBizErrCode.TRON_NODE_CONNECT_FAILED, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BizException(ScannerBizErrCode.TRON_NODE_CONNECT_FAILED, exception);
        }
    }

    private void validateStatus(HttpResponse<InputStream> response) {
        int statusCode = response.statusCode();
        // 请求太频繁，被节点限流了
        if (statusCode == 429) {
            close(response.body());
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RATE_LIMITED);
        }
        if (statusCode < 200 || statusCode >= 300) {
            close(response.body());
            throw BizException.of(ScannerBizErrCode.TRON_NODE_REMOTE_ERROR);
        }
    }

    private byte[] readResponseBody(InputStream responseBody) {
        int maxResponseBytes = Math.toIntExact(
            scannerProperties.getNode().getMaxResponseSize().toBytes());
        try (responseBody) {
            byte[] body = responseBody.readNBytes(maxResponseBytes + 1);
            if (body.length > maxResponseBytes) {
                throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
            }
            return body;
        } catch (IOException exception) {
            throw new BizException(ScannerBizErrCode.TRON_NODE_CONNECT_FAILED, exception);
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

    private void close(InputStream responseBody) {
        try {
            responseBody.close();
        } catch (IOException ignored) {
            // 响应已判定失败，关闭连接异常不覆盖原始错误类型。
        }
    }
}
