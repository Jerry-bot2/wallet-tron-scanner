package com.nb.tron.scanner;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

/**
 * TRON 扫描器启动入口。
 * <p>
 * Author: bin jack
 * Date: 02.10.26
 */
@EnableFeignClients
@SpringBootApplication
public class TronScannerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TronScannerApplication.class, args);
    }
}
