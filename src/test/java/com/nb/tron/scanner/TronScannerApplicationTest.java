package com.nb.tron.scanner;

import com.nb.tron.scanner.service.ITronMonitorAddressService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
@SpringBootTest(properties = "xxl.job.enabled=false")
class TronScannerApplicationTest {

    @MockitoBean
    private ITronMonitorAddressService monitorAddressService;

    @Test
    void contextLoads() {
    }
}
