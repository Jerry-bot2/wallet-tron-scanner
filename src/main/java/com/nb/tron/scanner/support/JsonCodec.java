package com.nb.tron.scanner.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Scanner JSON 编解码组件。
 *
 * <p>统一复用 Spring Boot 配置的 ObjectMapper，禁止业务代码自行创建。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
@RequiredArgsConstructor
public class JsonCodec {

    private final ObjectMapper objectMapper;

    /**
     * 将 JSON 字节转换为树形结构。
     *
     * @param json JSON 字节
     * @return JSON 树
     */
    public JsonNode readTree(byte[] json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * 将 JSON 字符串转换为树形结构。
     *
     * @param json JSON 字符串
     * @return JSON 树
     */
    public JsonNode readTree(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
