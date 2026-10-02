package com.nb.tron.scanner.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * TRON 扫描器运行配置。
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "nb.tron.scanner")
public class TronScannerProperties {

    /**
     * 当前扫描器处理的链编码。
     */
    private String chainCode = "TRON";

    /**
     * 当前扫描器连接的 TRON 网络，例如 MAINNET、NILE。
     */
    private String chainNetwork = "MAINNET";
}
