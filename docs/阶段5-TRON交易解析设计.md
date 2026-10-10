# 阶段 5：TRON 交易解析设计

本文冻结 TRON 区块解析的职责边界、统一输出模型和异常处理原则。阶段 5 只负责从区块中识别充值事实，不负责上报、落库、推进扫描游标或确认到账。

## 1. 输入与输出

交易解析的统一入口接收 `TronBlockData`：

```text
TronNodeManager 读取完整区块
→ TronBlockData（区块头、交易、交易回执）
→ TRX / TRC20 解析器
→ List<TronDepositEvent>
```

解析过程还需要两份只读内存数据：

1. `TronAddressIndex`：判断收款地址是否属于平台，并取得地址用途。
2. 币种配置快照：判断原生币和 TRC20 合约是否属于当前支持的充值币种。

解析器不得在解析过程中调用远程服务、写数据库或修改扫描检查点。同一个 `TronBlockData` 重复解析必须得到相同结果。

## 2. 统一充值事实模型

`TronDepositEvent` 字段如下：

| 字段 | 类型 | 说明 |
|---|---|---|
| `currency` | `String` | 币种编码，例如 `TRX`、`USDT` |
| `contractAddress` | `String` | TRC20 合约地址；原生 TRX 使用空字符串 |
| `txId` | `String` | 链上交易 ID |
| `eventIndex` | `int` | 同一交易内的稳定事件序号 |
| `blockNumber` | `long` | 发现交易时所在的区块高度 |
| `blockHash` | `String` | 发现交易时所在的区块 ID |
| `blockTimestamp` | `Instant` | 链上区块时间 |
| `fromAddress` | `String` | 付款地址，统一转换为 Base58Check |
| `toAddress` | `String` | 平台收款地址，统一转换为 Base58Check |
| `rawAmount` | `BigInteger` | 链上最小单位整数金额，不做精度换算 |

Scanner 不生成展示金额。`wallet-chain-server` 根据币种配置中的 `decimals` 完成金额换算和业务校验。

## 3. 幂等规则

充值事实的稳定唯一键为：

```text
chainCode + txId + eventIndex
```

`eventIndex` 规则：

- TRX：当前 TRON 协议只支持一笔交易包含一个 Contract，固定使用 `-1`。
- TRC20：使用交易回执中的日志序号，从 0 开始。

TRX 与 TRC20 的序号空间不会冲突，同一交易内多个 Transfer 日志也能稳定区分。区块高度和区块 Hash 是链事实，不参与唯一键；未固化阶段发生分叉时，由链服务根据重复上报更新或核验链事实。

## 4. 地址和金额规范

1. TRON 节点交易参数中的地址可能是 `41` 开头的 Hex，事件日志中的地址位于 Topic 或合约 Hex 字段。
2. 解析器统一转换为 Base58Check 后，再查询 `TronAddressIndex` 和币种配置。
3. Base58Check 地址区分大小写，不做大小写转换。
4. `rawAmount` 使用 `BigInteger`，禁止使用 `long`、`double` 或展示金额类型承载链上整数。
5. `rawAmount <= 0` 不生成充值事实。

## 5. TRX 解析边界

只处理 `TransferContract`：

1. 交易执行成功。
2. 读取付款地址、收款地址和 SUN 整数金额。
3. 收款地址存在于平台地址索引，且地址用途为客户充值。
4. 当前币种配置允许识别原生 TRX。
5. 生成一条 `TronDepositEvent`。

TRON 协议当前只支持一笔交易包含一个 Contract；字段定义为列表是为协议后续扩展。Scanner 遇到多个 Contract 时拒绝解析，避免错误推断执行结果与 Contract 的对应关系。

## 6. TRC20 解析边界

只处理成功交易回执中的 `Transfer(address,address,uint256)` 日志：

1. 日志 Topic0 必须等于标准 Transfer 事件签名。
2. 日志合约地址必须存在于当前币种配置快照。
3. 从 Topic1、Topic2 和 Data 读取付款地址、收款地址和原始整数金额。
4. 收款地址存在于平台地址索引，且地址用途为客户充值。
5. 使用日志在回执中的原始序号作为 `eventIndex`。
6. 生成一条 `TronDepositEvent`。

同一交易包含多个 Transfer 日志时，每个命中平台地址的日志分别生成充值事实。

## 7. 区块统一解析

`TronBlockParser` 是阶段 5 的唯一对外解析入口：

1. 按区块中的原始交易顺序逐笔处理。
2. 使用交易 ID 获取回执，并校验回执交易 ID 与区块高度。
3. 依次调用 TRX 和 TRC20 解析器。
4. 保持交易顺序和日志顺序汇总充值事实。
5. 全部交易解析成功后才返回完整结果。

统一入口不读取节点、不写数据库、不调用链服务，也不推进扫描检查点。

## 8. 数据异常处理

处理原则以“不漏记资金”为优先：

- 不支持的合约类型、非 Transfer 日志、非目标合约和非平台收款地址直接忽略。
- 交易或回执明确失败时不生成充值事实。
- 已确认属于目标币种或目标 Transfer 的数据，如果地址、金额、Topic 或必填字段格式错误，则整个区块解析失败。
- 区块解析失败时不得上报部分结果，也不得推进扫描检查点，等待下一轮重新读取和解析。

## 9. 类职责

阶段 5 按以下职责拆分：

```text
TronCurrencyIndex       币种配置内存快照
TronAddressCodec       Hex 与 Base58Check 地址转换
TrxTransferParser      解析 TransferContract
Trc20TransferParser    解析 Transfer 日志
TronBlockParser        编排整块解析并汇总充值事实
```

解析器保持无状态，配置和地址数据都通过只读快照查询。`TronBlockParser` 返回完整结果后，后续扫描编排才允许上报和推进检查点。

## 10. 样本测试与验收

使用以下固定节点响应样本完成阶段 5 验收：

- `samples/tron/block-100.json`：包含五笔不同类型的交易。
- `samples/tron/transaction-info-100.json`：包含与交易一一对应的执行回执和日志。

验收链路：

```text
FullNode 区块与回执样本
→ TronNodeClient
→ TronBlockData
→ TronBlockParser
→ TRX / TRC20 充值事实
```

验收结果：

| 样本 | 预期结果 |
|---|---|
| 成功的 TRX 转账到平台地址 | 生成 TRX 充值事实 |
| 成功的 USDT Transfer 到平台地址 | 生成 USDT 充值事实 |
| 执行失败的交易 | 忽略 |
| 未配置的 TRC20 合约 | 忽略 |
| 转账到非平台地址 | 忽略 |
| 同一区块重复解析 | 两次结果完全一致 |

## 11. 本阶段不负责

- 不调用充值发现上报接口。
- 不写本地充值表或 Outbox。
- 不推进 `HEAD_BLOCK` 扫描检查点。
- 不确认充值是否固化到账。
