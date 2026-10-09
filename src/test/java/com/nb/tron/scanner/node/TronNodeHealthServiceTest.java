package com.nb.tron.scanner.node;

import com.nb.tron.sdk.node.TronNodePool;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Scanner节点健康任务只负责触发SDK节点池。
 * <p>
 * Author: bin jack
 * Date: 09.10.26
 */
class TronNodeHealthServiceTest {

    @Test
    void shouldDelegateRefreshToSdkPool() {
        TronNodePool nodePool = mock(TronNodePool.class);
        TronNodeHealthService service = new TronNodeHealthService(nodePool);

        service.refreshNodeStates();

        verify(nodePool).refresh();
    }
}
