package com.nb.tron.scanner.client.tron;

import com.fasterxml.jackson.databind.JsonNode;
import com.nb.core.exception.BizAssert;
import com.nb.core.exception.BizException;
import com.nb.tron.scanner.config.TronNodeEndpointProperties;
import com.nb.tron.scanner.enums.TronNodeRole;
import com.nb.tron.scanner.exception.ScannerBizErrCode;
import com.nb.tron.scanner.model.TronBlockData;
import com.nb.tron.scanner.model.TronNodeHeight;
import com.nb.tron.scanner.model.TronTransaction;
import com.nb.tron.scanner.model.TronTransactionReceipt;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.nb.tron.scanner.constant.TronConstants.GENESIS_BLOCK_HEIGHT;
import static com.nb.tron.scanner.constant.TronHttpConstants.EMPTY_REQUEST;
import static com.nb.tron.scanner.constant.TronHttpConstants.FULL_BLOCK_BY_NUM_PATH;
import static com.nb.tron.scanner.constant.TronHttpConstants.FULL_NOW_BLOCK_PATH;
import static com.nb.tron.scanner.constant.TronHttpConstants.SOLID_BLOCK_BY_NUM_PATH;
import static com.nb.tron.scanner.constant.TronHttpConstants.SOLID_NOW_BLOCK_PATH;
import static com.nb.tron.scanner.constant.TronHttpConstants.TRANSACTION_INFO_BY_BLOCK_PATH;

/**
 * TRON 节点协议客户端。
 *
 * <p>负责固定接口调用和协议响应转换，不选择节点、不重试请求。</p>
 * <p>
 * Author: bin jack
 * Date: 03.10.26
 */
@Component
@RequiredArgsConstructor
public class TronNodeClient {

    private final TronHttpTransport httpTransport;

    /**
     * 查询 FullNode 当前已经产生到哪个区块
     *
     * <p>
     * Scanner 用这个高度确定本轮最多扫描到哪里。该高度可能尚未固化，
     * 只能用于及时发现充值，不能直接认定充值已经到账。
     * </p>
     *
     * @param endpoint 本次查询使用的 FullNode
     * @return FullNode 最新区块的高度、区块 ID 和链上时间
     */
    public TronNodeHeight getHeadHeight(TronNodeEndpointProperties endpoint) {
        requireRole(endpoint, TronNodeRole.FULL_NODE);
        JsonNode response = httpTransport.post(endpoint, FULL_NOW_BLOCK_PATH, EMPTY_REQUEST);
        return toNodeHeight(endpoint.getCode(), readBlockHeader(response, null));
    }

    /**
     * 查询 SolidityNode 当前已经固化到哪个区块
     *
     * <p>
     * 保留为可选节点能力。Head 扫描和分叉回退不依赖该接口，
     * 充值固化确认由 wallet-chain-server 独立核验。
     * </p>
     *
     * @param endpoint 本次查询使用的 SolidityNode
     * @return 最新固化区块的高度、区块 ID 和链上时间
     */
    public TronNodeHeight getSolidHeight(TronNodeEndpointProperties endpoint) {
        requireRole(endpoint, TronNodeRole.SOLIDITY_NODE);
        JsonNode response = httpTransport.post(endpoint, SOLID_NOW_BLOCK_PATH, EMPTY_REQUEST);
        return toNodeHeight(endpoint.getCode(), readBlockHeader(response, null));
    }

    /**
     * 查询指定高度的区块头，不读取区块交易
     *
     * <p>
     * 1. 启动时读取第 0 个区块，核对创世区块 ID，防止连错网络。<br>
     * 2. 扫描时核对已扫描区块，以及读取回执期间区块 Hash 是否发生变化。
     * </p>
     *
     * @param endpoint    本次查询使用的 FullNode 或 SolidityNode
     * @param blockHeight 要查询的区块高度
     * @return 指定高度区块的高度、区块 ID 和链上时间
     */
    public TronNodeHeight getBlockHeaderByHeight(TronNodeEndpointProperties endpoint, long blockHeight) {
        BizAssert.notNull(endpoint, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
        BizAssert.notNull(endpoint.getRole(), ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
        requireBlockHeight(blockHeight);
        String path = endpoint.getRole() == TronNodeRole.SOLIDITY_NODE
            ? SOLID_BLOCK_BY_NUM_PATH
            : FULL_BLOCK_BY_NUM_PATH;
        JsonNode response = httpTransport.post(endpoint, path, blockRequest(blockHeight));
        return toNodeHeight(endpoint.getCode(), readBlockHeader(response, blockHeight));
    }

    /**
     * 读取指定高度的完整区块数据
     *
     * <p>
     * 1. 第一次请求：读取区块 Hash 和交易列表，得到转账或合约调用内容。<br>
     * 2. 第二次请求：读取这些交易的执行回执，得到实际产生的 Transfer 日志。<br>
     * 3. 第三次请求：重新读取同一高度的区块，比较 Hash，确认读取回执期间区块未发生变化。
     * </p>
     * <p>前两次请求都需要：区块包含交易内容，但不包含执行产生的 Transfer 日志，
     * USDT／TRC20 转账要从回执日志中识别。第三次请求用于检查前两次读取之间是否发生分叉。
     * 三次请求都使用同一个 FullNode，任意一次失败或 Hash 改变，本次读取整体失败，由节点管理器处理重试。</p>
     *
     * @param endpoint    本次读取使用的 FullNode
     * @param blockHeight 要读取的区块高度
     * @return 包含区块头、完整交易和执行回执的区块数据
     */
    public TronBlockData getBlockDataByHeight(TronNodeEndpointProperties endpoint, long blockHeight) {
        requireRole(endpoint, TronNodeRole.FULL_NODE);
        requireBlockHeight(blockHeight);

        String requestBody = blockRequest(blockHeight);

        // 1. 第一次请求：读取区块及交易列表，拿到区块 Hash、普通 TRX 转账或合约调用内容。
        JsonNode blockResponse = httpTransport.post(endpoint, FULL_BLOCK_BY_NUM_PATH, requestBody);
        BlockHeader blockHeader = readBlockHeader(blockResponse, blockHeight);
        List<TronTransaction> transactions = readTransactions(blockResponse.path("transactions"));

        // 2. 第二次请求：读取执行回执，拿到 USDT／TRC20 实际转账的 Transfer 日志。
        // 区块里只有交易内容，没有这些日志，因此只请求第一次会漏掉合约执行产生的转账。
        JsonNode receiptResponse = httpTransport.post(endpoint, TRANSACTION_INFO_BY_BLOCK_PATH, requestBody);
        Map<String, TronTransactionReceipt> receipts = readReceipts(receiptResponse, blockHeight);

        // 两次读取的交易与回执必须按交易 ID 完整对应，缺少或多出回执都不能继续解析。
        boolean completeReceipts = transactions.stream()
            .map(TronTransaction::transactionId)
            .allMatch(receipts::containsKey)
            && receipts.size() == transactions.size();
        if (!completeReceipts) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        }

        // 3. 第三次请求：再读同一高度的区块，只取 Hash 与第一次比较。
        // 前两次是独立请求，期间可能分叉。例如第一次是 100/A，第三次变为 100/B，
        // 就丢弃本次区块和回执，由节点管理器整块重读，避免混用不同分支的数据。
        TronNodeHeight currentBlock = getBlockHeaderByHeight(endpoint, blockHeight);
        BizAssert.isTrue(blockHeader.blockId().equals(currentBlock.blockId()),
            ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);

        return new TronBlockData(
            endpoint.getCode(),
            blockHeader.blockHeight(),
            blockHeader.blockId(),
            blockHeader.parentBlockId(),
            blockHeader.blockTimestamp(),
            transactions,
            receipts);
    }

    private BlockHeader readBlockHeader(JsonNode response, Long requestedHeight) {
        if (!response.isObject() || response.isEmpty()) {
            ScannerBizErrCode errorCode = requestedHeight == null
                ? ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID
                : ScannerBizErrCode.TRON_BLOCK_NOT_FOUND;
            throw BizException.of(errorCode);
        }

        JsonNode rawHeader = response.path("block_header").path("raw_data");
        String blockId = response.path("blockID").textValue();
        String parentBlockId = rawHeader.path("parentHash").textValue();

        if (!rawHeader.isObject() || !StringUtils.hasText(blockId)) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        }

        // TRON 的创世区块可能省略值为 0 的 number 和 timestamp；其他高度仍要求字段完整。
        boolean genesisBlock = requestedHeight != null && requestedHeight == GENESIS_BLOCK_HEIGHT;
        long blockHeight = readHeaderNumber(rawHeader, "number", genesisBlock);
        long blockTimestamp = readHeaderNumber(rawHeader, "timestamp", genesisBlock);
        if (blockHeight < 0
            || blockTimestamp < 0
            || (requestedHeight != null && blockHeight != requestedHeight)) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        }
        if (blockHeight > 0 && !StringUtils.hasText(parentBlockId)) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        }

        return new BlockHeader(
            blockHeight,
            blockId,
            parentBlockId,
            Instant.ofEpochMilli(blockTimestamp));
    }

    private long readHeaderNumber(JsonNode header, String fieldName, boolean genesisBlock) {
        JsonNode value = header.path(fieldName);
        if (genesisBlock && value.isMissingNode()) {
            return 0L;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        }
        return value.longValue();
    }

    private List<TronTransaction> readTransactions(JsonNode transactionNodes) {
        if (transactionNodes.isMissingNode() || transactionNodes.isNull()) {
            return List.of();
        }
        if (!transactionNodes.isArray()) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        }

        List<TronTransaction> transactions = new ArrayList<>(transactionNodes.size());
        for (JsonNode transactionNode : transactionNodes) {
            String transactionId = transactionNode.path("txID").textValue();
            if (!StringUtils.hasText(transactionId)) {
                throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
            }
            transactions.add(new TronTransaction(transactionId, transactionNode.toString()));
        }
        return transactions;
    }

    private Map<String, TronTransactionReceipt> readReceipts(JsonNode receiptNodes,
                                                             long requestedHeight) {
        if (!receiptNodes.isArray()) {
            throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
        }

        Map<String, TronTransactionReceipt> receipts = new LinkedHashMap<>();
        for (JsonNode receiptNode : receiptNodes) {
            String transactionId = receiptNode.path("id").textValue();
            JsonNode blockHeightNode = receiptNode.path("blockNumber");
            boolean validReceipt = StringUtils.hasText(transactionId)
                && blockHeightNode.isIntegralNumber()
                && blockHeightNode.canConvertToLong()
                && blockHeightNode.longValue() == requestedHeight;
            if (!validReceipt) {
                throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
            }

            TronTransactionReceipt receipt = new TronTransactionReceipt(
                transactionId,
                requestedHeight,
                receiptNode.toString());
            if (receipts.putIfAbsent(transactionId, receipt) != null) {
                throw BizException.of(ScannerBizErrCode.TRON_NODE_RESPONSE_INVALID);
            }
        }
        return receipts;
    }

    private TronNodeHeight toNodeHeight(String nodeCode, BlockHeader blockHeader) {
        return new TronNodeHeight(
            nodeCode,
            blockHeader.blockHeight(),
            blockHeader.blockId(),
            blockHeader.blockTimestamp());
    }

    private void requireRole(TronNodeEndpointProperties endpoint, TronNodeRole expectedRole) {
        BizAssert.notNull(endpoint, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
        BizAssert.isTrue(endpoint.getRole() == expectedRole, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
    }

    private void requireBlockHeight(long blockHeight) {
        BizAssert.isTrue(blockHeight >= 0, ScannerBizErrCode.TRON_NODE_CONFIG_INVALID);
    }

    private String blockRequest(long blockHeight) {
        return "{\"num\":" + blockHeight + "}";
    }

    private record BlockHeader(long blockHeight,
                               String blockId,
                               String parentBlockId,
                               Instant blockTimestamp) {
    }
}
