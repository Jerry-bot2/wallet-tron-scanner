package com.nb.tron.scanner;

import com.nb.chain.client.api.ChainScannerClient;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * TRON 扫描器启动入口。
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
@EnableFeignClients(basePackageClasses = ChainScannerClient.class)
@MapperScan("com.nb.tron.scanner.mapper")
@SpringBootApplication
public class TronScannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TronScannerApplication.class, args);
    }
}
