package com.nb.tron.scanner;

import com.nb.tron.scanner.service.ITronMonitorAddressService;
import com.nb.tron.scanner.node.TronNodeStartupValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Author: bin jack
 * Date: 02.10.26
 */
@SpringBootTest(properties = {
    "xxl.job.enabled=false",
    "nb.tron.scanner.expected-genesis-block-id=0000000000000000000000000000000000000000000000000000000000000000",
    "nb.tron.scanner.start-block-height=1",
    "nb.tron.scanner.node.nodes[0].code=full-primary",
    "nb.tron.scanner.node.nodes[0].role=FULL_NODE",
    "nb.tron.scanner.node.nodes[0].priority=1",
    "nb.tron.scanner.node.nodes[0].base-url=http://127.0.0.1:8090",
    "nb.tron.scanner.node.nodes[1].code=solidity-primary",
    "nb.tron.scanner.node.nodes[1].role=SOLIDITY_NODE",
    "nb.tron.scanner.node.nodes[1].priority=1",
    "nb.tron.scanner.node.nodes[1].base-url=http://127.0.0.1:8091"
})
class TronScannerApplicationTest {

    @MockitoBean
    private ITronMonitorAddressService monitorAddressService;

    @MockitoBean
    private TronNodeStartupValidator nodeStartupValidator;

    @Test
    void contextLoads() {
    }
}
