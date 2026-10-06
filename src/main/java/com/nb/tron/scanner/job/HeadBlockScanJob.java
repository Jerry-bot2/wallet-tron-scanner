package com.nb.tron.scanner.job;

import com.nb.job.core.NbJobContext;
import com.nb.job.core.NbJobHandler;
import com.nb.tron.scanner.biz.HeadBlockScanService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * TRON Head 区块扫描任务
 *
 * <p>由 XXL-JOB 单机串行触发。任何节点读取、解析、Kafka 发送或检查点更新异常
 * 都会结束本轮任务，下一轮继续从数据库检查点的下一高度处理。
 * 分叉已成功回退时，只记录警告并正常结束本轮；回退失败仍交给 XXL-JOB 报错。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
@RequiredArgsConstructor
public class HeadBlockScanJob extends NbJobHandler {

    private final HeadBlockScanService headBlockScanService;

    @Override
    protected Integer doExecute(NbJobContext ignored) {
        return headBlockScanService.scanBlocks();
    }
}
