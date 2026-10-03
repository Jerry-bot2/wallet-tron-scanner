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
import com.nb.tron.scanner.model.TronTransactionReceipt;
import com.nb.tron.scanner.support.JsonCodec;
import com.nb.tron.scanner.support.TronAddressCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static com.nb.tron.scanner.constant.TronTransactionConstants.CONTRACT_SUCCESS;
import static com.nb.tron.scanner.constant.TronTransactionConstants.TRANSACTION_RECEIPT_FAILED;
import static com.nb.tron.scanner.constant.TronTransactionConstants.TRC20_TRANSFER_AMOUNT_BYTES;
import static com.nb.tron.scanner.constant.TronTransactionConstants.TRC20_TRANSFER_EVENT_TOPIC;
import static com.nb.tron.scanner.constant.TronTransactionConstants.TRC20_TRANSFER_TOPIC_COUNT;

/**
 * TRC20 Transfer 事件解析器
 *
 * <p>核心规则：</p>
 * <ol>
 *     <li>只解析执行成功交易的回执日志；</li>
 *     <li>先用日志合约地址匹配当前支持的 TRC20 币种；</li>
 *     <li>只识别标准 {@code Transfer(address,address,uint256)} 事件；</li>
 *     <li>从 Topic1、Topic2 和 Data 读取付款地址、收款地址和原始金额；</li>
 *     <li>收款地址用途为 {@code DEPOSIT} 时才生成充值事实；</li>
 *     <li>使用日志在回执中的原始下标作为 {@code eventIndex}。</li>
 * </ol>
 *
 * <p>一个交易可以包含多个 Transfer 日志，每个命中平台充值地址的日志分别生成一条充值事实。
 * 非目标合约、非 Transfer 日志和非平台收款地址直接忽略；目标 Transfer 数据格式错误时抛出解析异常，
 * 由上层停止本区块处理且不推进扫描检查点。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
@RequiredArgsConstructor
public class Trc20TransferParser {

    private final JsonCodec jsonCodec;

    private final TronAddressCodec addressCodec;

    private final TronAddressIndex addressIndex;

    private final TronCurrencyIndex currencyIndex;

    private final TronScannerProperties scannerProperties;

    /**
     * 解析一笔交易回执中的 TRC20 Transfer 日志。
     *
     * <p>
     * 1.确认交易执行成功；
     * 2.按回执原始顺序遍历日志；
     * 3.识别受支持合约的标准 Transfer；
     * 4.命中平台充值地址后生成充值事实。
     * </p>
     *
     * @return 本交易命中的全部 TRC20 充值事实
     */
    public List<TronDepositEvent> parse(TronBlockData blockData,
                                        TronTransaction transaction,
                                        TronTransactionReceipt receipt) {
        JsonNode transactionNode = readJson(transaction.rawJson());
        if (!isSuccessful(transactionNode.path("ret"))) {
            return List.of();
        }

        JsonNode receiptNode = readJson(receipt.rawJson());
        if (TRANSACTION_RECEIPT_FAILED.equals(receiptNode.path("result").textValue())) {
            return List.of();
        }

        JsonNode logNodes = receiptNode.path("log");
        if (logNodes.isMissingNode() || logNodes.isNull()) {
            return List.of();
        }
        if (!logNodes.isArray()) {
            throw invalidTransaction();
        }

        List<TronDepositEvent> depositEvents = new ArrayList<>();
        for (int logIndex = 0; logIndex < logNodes.size(); logIndex++) {
            TronDepositEvent depositEvent = parseLog(
                blockData,
                transaction,
                logNodes.get(logIndex),
                logIndex);
            if (depositEvent != null) {
                depositEvents.add(depositEvent);
            }
        }
        return List.copyOf(depositEvents);
    }

    private TronDepositEvent parseLog(TronBlockData blockData,
                                      TronTransaction transaction,
                                      JsonNode logNode,
                                      int logIndex) {
        if (!logNode.isObject()) {
            throw invalidTransaction();
        }

        TronCurrencyConfig currency = findSupportedCurrency(logNode);
        if (!isSupportedTransferEvent(currency, logNode)) {
            return null;
        }

        JsonNode topics = logNode.path("topics");
        if (topics.size() != TRC20_TRANSFER_TOPIC_COUNT) {
            throw invalidTransaction();
        }

        String fromAddress = addressCodec.fromTopic(topics.get(1).textValue());
        String toAddress = addressCodec.fromTopic(topics.get(2).textValue());
        BigInteger rawAmount = readRawAmount(logNode.path("data").textValue());
        if (rawAmount.signum() == 0
            || addressIndex.findPurpose(toAddress) != AddressPurpose.DEPOSIT) {
            return null;
        }

        return new TronDepositEvent(
            scannerProperties.getChainCode(),
            scannerProperties.getChainNetwork(),
            currency.currency(),
            currency.contractAddress(),
            transaction.transactionId(),
            logIndex,
            blockData.blockHeight(),
            blockData.blockId(),
            blockData.blockTimestamp(),
            fromAddress,
            toAddress,
            rawAmount);
    }

    private TronCurrencyConfig findSupportedCurrency(JsonNode logNode) {
        String contractAddress = logNode.path("address").textValue();
        if (!StringUtils.hasText(contractAddress)) {
            return null;
        }
        return currencyIndex.findByContractAddress(addressCodec.fromHex(contractAddress));
    }

    /**
     * 判断当前日志是否属于 Scanner 支持的 TRC20 转账。
     *
     * <p>
     * 1.合约地址必须存在于币种配置快照，表示该币种受支持；
     * 2.Topic0 必须是标准 Transfer 事件签名，授权等其他事件不会进入充值解析。
     * </p>
     */
    private boolean isSupportedTransferEvent(TronCurrencyConfig currency, JsonNode logNode) {
        return currency != null && isTransferEvent(logNode.path("topics"));
    }

    private boolean isTransferEvent(JsonNode topics) {
        if (!topics.isArray() || topics.isEmpty() || !topics.get(0).isTextual()) {
            return false;
        }
        //所有符合 ERC20/TRC20 标准的 Transfer 事件，Topic0 都是这个值 = TRC20_TRANSFER_EVENT_TOPIC
        //topics[0] = Transfer 事件签名
        String eventTopic = removeHexPrefix(topics.get(0).textValue());
        return TRC20_TRANSFER_EVENT_TOPIC.equalsIgnoreCase(eventTopic);
    }

    private BigInteger readRawAmount(String value) {
        if (!StringUtils.hasText(value)) {
            throw invalidTransaction();
        }
        try {
            byte[] amountBytes = HexFormat.of().parseHex(removeHexPrefix(value));
            if (amountBytes.length != TRC20_TRANSFER_AMOUNT_BYTES) {
                throw invalidTransaction();
            }
            return new BigInteger(1, amountBytes);
        } catch (IllegalArgumentException exception) {
            throw invalidTransaction();
        }
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

    private JsonNode readJson(String rawJson) {
        try {
            JsonNode jsonNode = jsonCodec.readTree(rawJson);
            if (jsonNode == null || !jsonNode.isObject()) {
                throw invalidTransaction();
            }
            return jsonNode;
        } catch (UncheckedIOException | IllegalArgumentException exception) {
            throw invalidTransaction();
        }
    }

    private String removeHexPrefix(String value) {
        return value.startsWith("0x") || value.startsWith("0X")
            ? value.substring(2)
            : value;
    }

    private BizException invalidTransaction() {
        return BizException.of(ScannerBizErrCode.TRON_TRANSACTION_INVALID);
    }
}
