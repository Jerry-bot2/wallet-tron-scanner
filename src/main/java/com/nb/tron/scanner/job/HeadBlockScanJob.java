package com.nb.tron.scanner.job;

import com.nb.core.exception.BizException;
import com.nb.job.core.NbJobContext;
import com.nb.job.core.NbJobHandler;
import com.nb.tron.scanner.biz.HeadBlockScanService;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Component
@RequiredArgsConstructor
public class HeadBlockScanJob extends NbJobHandler {

    private final HeadBlockScanService headBlockScanService;

    @Override
    protected Integer doExecute(NbJobContext ignored) {
        try {
            int scannedCount = headBlockScanService.scanBlocks();
            log.info("TRON Head区块扫描完成，scannedCount={}", scannedCount);
            return scannedCount;
        } catch (BizException exception) {
            // 这个结果只在回退事务成功后抛出，下轮从共同区块的下一块重扫。
            if (exception.getErrorCode() != ScannerBizErrCode.HEAD_SCAN_FORK_DETECTED) {
                throw exception;
            }
            log.warn("TRON Head分叉回退完成，本轮结束，下轮重扫，message={}", exception.getMessage());
            return 0;
        }
    }
}
