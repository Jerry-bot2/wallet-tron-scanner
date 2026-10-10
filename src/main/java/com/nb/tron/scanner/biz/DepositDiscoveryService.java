package com.nb.tron.scanner.biz;

import com.nb.chain.client.enums.AddressPurpose;
import com.nb.tron.scanner.config.TronScannerProperties;
import com.nb.tron.scanner.constant.TronConstants;
import com.nb.tron.scanner.index.TronAddressIndex;
import com.nb.tron.scanner.index.TronCurrencyIndex;
import com.nb.tron.scanner.model.TronCurrencyConfig;
import com.nb.tron.scanner.model.TronDepositEvent;
import com.nb.tron.scanner.support.TronSdkCalls;
import com.nb.tron.sdk.model.TronAsset;
import com.nb.tron.sdk.model.TronBlockData;
import com.nb.tron.sdk.model.TronTransferEvent;
import com.nb.tron.sdk.parser.TronBlockParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 充值发现业务：SDK返回标准转账，本类只匹配币种和平台充值地址、组装充值事实
 * <p>
 * Author: bin jack
 * Date: 07.10.26
 */
@Service
@RequiredArgsConstructor
public class DepositDiscoveryService {

    private final TronBlockParser blockParser;
    private final TronAddressIndex addressIndex;
    private final TronCurrencyIndex currencyIndex;
    private final TronScannerProperties scannerProperties;

    /**
     * 1. 固定本轮币种快照，交给SDK解析这些资产的完整区块转账。<br>
     * 2. 只保留收款地址属于平台充值地址的转账。<br>
     * 3. 补充运行网络和业务币种，返回待发送的充值事实；本类不解析协议JSON。
     */
    public List<TronDepositEvent> discover(TronBlockData block) {
        Map<TronAsset, TronCurrencyConfig> currencies = currencyIndex.snapshot();
        List<TronTransferEvent> transfers = TronSdkCalls.execute(() -> blockParser.parse(block, currencies.keySet()));
        return transfers.stream()
            .filter(event -> addressIndex.findPurpose(event.transfer().toAddress()) == AddressPurpose.DEPOSIT)
            .map(event -> toDeposit(block, event, currencies.get(event.asset())))
            .toList();
    }

    private TronDepositEvent toDeposit(TronBlockData block, TronTransferEvent event, TronCurrencyConfig currency) {
        return new TronDepositEvent(TronConstants.CHAIN_CODE, scannerProperties.getChainNetwork(),
            currency.currency(), currency.contractAddress(), event.txId(), event.eventIndex(),
            block.blockHeight(), block.blockId(), block.blockTimestamp(), event.transfer().fromAddress(),
            event.transfer().toAddress(), event.transfer().rawAmount());
    }
}
