package com.nb.tron.scanner.config;

import com.nb.tron.scanner.enums.TronNodeRole;
import lombok.Getter;
import lombok.Setter;

import java.net.URI;

/**
 * 单个 TRON 节点配置
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Getter
@Setter
public class TronNodeEndpointProperties {

    /**
     * 节点唯一编码，用于日志、指标和运行状态索引。
     */
    private String code;

    /**
     * 节点提供的链数据视图。
     */
    private TronNodeRole role;

    /**
     * 节点选择优先级，数值越小优先级越高。
     */
    private int priority;

    /**
     * 节点基础地址，不包含 /wallet 或 /walletsolidity 路径。
     */
    private URI baseUrl;

    /**
     * TronGrid 访问凭证，自建节点允许为空。
     */
    private String apiKey;
}
