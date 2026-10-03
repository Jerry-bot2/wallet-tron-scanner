package com.nb.tron.scanner.parser;

import com.nb.core.exception.BizAssert;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.model.TronTransaction;
import com.nb.tron.scanner.model.TronTransactionReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * TRON 区块统一解析入口
 *
 * <p>核心流程：</p>
 * <ol>
 *     <li>按区块中的原始交易顺序逐笔处理；</li>
 *     <li>为每笔交易取得同一高度、同一交易 ID 的执行回执；</li>
 *     <li>依次调用 TRX 和 TRC20 解析器识别充值事实；</li>
 *     <li>整个区块解析成功后，返回不可变的完整结果。</li>
 * </ol>
 *
 * <p>本类只负责编排，不读取节点、不写数据库，也不推进扫描检查点。
 * 任意一笔交易缺少回执或解析失败时直接抛出异常，不会返回部分结果。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
@RequiredArgsConstructor
public class TronBlockParser {

    private final TrxTransferParser trxTransferParser;

    private final Trc20TransferParser trc20TransferParser;

    /**
     * 解析完整区块中的全部充值事实。
     *
     * @param blockData 节点返回的完整区块、交易和回执数据
     * @return 按交易顺序和日志顺序排列的充值事实
     */
    public List<TronDepositEvent> parse(TronBlockData blockData) {
        BizAssert.notNull(blockData, ScannerBizErrCode.TRON_BLOCK_DATA_INCOMPLETE);
        BizAssert.isTrue(
            blockData.transactions().size() == blockData.receipts().size(),
            ScannerBizErrCode.TRON_BLOCK_DATA_INCOMPLETE);

        List<TronDepositEvent> depositEvents = new ArrayList<>();
        for (TronTransaction transaction : blockData.transactions()) {
            TronTransactionReceipt receipt = requireReceipt(blockData, transaction);
            // 尝试识别 TRX 充值
            // TrxTransferParser 检查是否为 TransferContract
            depositEvents.addAll(trxTransferParser.parse(blockData, transaction));
            // 尝试识别 TRC20 充值
            // Trc20TransferParser 检查回执是否包含受支持币种的标准 Transfer 日志
            depositEvents.addAll(trc20TransferParser.parse(blockData, transaction, receipt));
        }
        return List.copyOf(depositEvents);
    }

    /**
     * 获取当前交易的回执，并确认回执属于当前交易和当前区块。
     */
    private TronTransactionReceipt requireReceipt(TronBlockData blockData,
                                                  TronTransaction transaction) {
        TronTransactionReceipt receipt = blockData.receipts().get(transaction.transactionId());
        boolean matched = receipt != null
            && transaction.transactionId().equals(receipt.transactionId())
            && receipt.blockHeight() == blockData.blockHeight();
        BizAssert.isTrue(matched, ScannerBizErrCode.TRON_BLOCK_DATA_INCOMPLETE);
        return receipt;
    }
}
