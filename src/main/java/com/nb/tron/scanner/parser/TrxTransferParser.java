package com.nb.tron.scanner.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.nb.chain.client.enums.AddressPurpose;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.model.TronTransaction;
import com.nb.tron.scanner.support.JsonCodec;
import com.nb.tron.scanner.support.TronAddressCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.UncheckedIOException;
import java.util.List;

import static com.nb.tron.scanner.constant.TronTransactionConstants.CONTRACT_SUCCESS;
import static com.nb.tron.scanner.constant.TronTransactionConstants.TRANSFER_CONTRACT_TYPE;

/**
 * TRX 原生转账解析器。
 *
 * <p>核心规则：</p>
 * <ol>
 *     <li>只识别 {@code raw_data.contract[0].type = TransferContract}；</li>
 *     <li>只有 {@code ret[0].contractRet = SUCCESS} 才继续解析；</li>
 *     <li>从合约参数读取付款地址、收款地址和 SUN 原始金额；</li>
 *     <li>地址统一转换为 Base58Check，金额必须为正整数；</li>
 *     <li>收款地址用途为 {@code DEPOSIT} 时才生成充值事实；</li>
 *     <li>TRX 的 {@code eventIndex} 固定为 {@code -1}。</li>
 * </ol>
 *
 * <p>失败交易、其他合约和非平台充值地址直接忽略。目标 TRX 转账字段不完整或格式错误时
 * 抛出解析异常，由上层停止本区块处理且不推进扫描检查点。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
@RequiredArgsConstructor
public class TrxTransferParser {

    private final JsonCodec jsonCodec;

    private final TronAddressCodec addressCodec;

    private final TronAddressIndex addressIndex;

    private final TronCurrencyIndex currencyIndex;

    private final TronScannerProperties scannerProperties;

    /**
     * 解析一笔交易中的 TRX 转账
     *
     * <p>
     * 1.读取当前协议支持的唯一合约；
     * 2.只处理执行成功的 TransferContract；
     * 3.将付款和收款地址统一转换为 Base58Check；
     * 4.收款地址命中平台充值地址后生成充值事实。
     * </p>
     *
     * @return 命中平台充值地址时返回一条充值事实，否则返回空列表
     */
    public List<TronDepositEvent> parse(TronBlockData blockData,
                                        TronTransaction transaction) {
        TronCurrencyConfig nativeCurrency = currencyIndex.findNativeCurrency();
        if (nativeCurrency == null) {
            return List.of();
        }

        JsonNode transactionNode = readTransaction(transaction.rawJson());
        JsonNode contractNodes = transactionNode.path("raw_data").path("contract");
        if (!contractNodes.isArray() || contractNodes.size() != 1) {
            throw invalidTransaction();
        }

        JsonNode contractNode = contractNodes.get(0);
        String contractType = contractNode.path("type").textValue();
        if (!StringUtils.hasText(contractType)) {
            throw invalidTransaction();
        }
        if (!TRANSFER_CONTRACT_TYPE.equals(contractType)
            || !isSuccessful(transactionNode.path("ret"))) {
            return List.of();
        }

        TronDepositEvent depositEvent = parseTransfer(
            blockData,
            transaction,
            nativeCurrency,
            contractNode);
        return depositEvent == null ? List.of() : List.of(depositEvent);
    }

    private TronDepositEvent parseTransfer(TronBlockData blockData,
                                           TronTransaction transaction,
                                           TronCurrencyConfig nativeCurrency,
                                           JsonNode contractNode) {
        JsonNode transfer = contractNode.path("parameter").path("value");
        String ownerAddress = transfer.path("owner_address").textValue();
        String receiverAddress = transfer.path("to_address").textValue();
        JsonNode amountNode = transfer.path("amount");
        if (!StringUtils.hasText(ownerAddress)
            || !StringUtils.hasText(receiverAddress)
            || !amountNode.isIntegralNumber()
            || amountNode.bigIntegerValue().signum() <= 0) {
            throw invalidTransaction();
        }

        String fromAddress = addressCodec.fromHex(ownerAddress);
        String toAddress = addressCodec.fromHex(receiverAddress);
        if (addressIndex.findPurpose(toAddress) != AddressPurpose.DEPOSIT) {
            return null;
        }

        return new TronDepositEvent(
            scannerProperties.getChainCode(),
            scannerProperties.getChainNetwork(),
            nativeCurrency.currency(),
            nativeCurrency.contractAddress(),
            transaction.transactionId(),
            -1,
            blockData.blockHeight(),
            blockData.blockId(),
            blockData.blockTimestamp(),
            fromAddress,
            toAddress,
            amountNode.bigIntegerValue());
    }

    private boolean isSuccessful(JsonNode resultNodes) {
        if (!resultNodes.isArray()) {
            throw invalidTransaction();
        }
        if (resultNodes.size() != 1) {
            throw invalidTransaction();
        }
        String contractResult = resultNodes.get(0).path("contractRet").textValue();
        if (!StringUtils.hasText(contractResult)) {
            throw invalidTransaction();
        }
        return CONTRACT_SUCCESS.equals(contractResult);
    }

    private JsonNode readTransaction(String rawJson) {
        try {
            JsonNode transactionNode = jsonCodec.readTree(rawJson);
            if (transactionNode == null || !transactionNode.isObject()) {
                throw invalidTransaction();
            }
            return transactionNode;
        } catch (UncheckedIOException | IllegalArgumentException exception) {
            throw invalidTransaction();
        }
    }

    private BizException invalidTransaction() {
        return BizException.of(ScannerBizErrCode.TRON_TRANSACTION_INVALID);
    }
}
