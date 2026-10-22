# 阶段4：TRON节点能力契约

本文冻结 `wallet-tron-scanner` 访问 TRON 节点的边界。业务代码不拼接节点 URL，不自行判断主备节点，也不把未固化 Head 高度作为充值扫描上界。

## 1. 技术决策

1. 使用 java-tron 原生 HTTP API，不引入 Trident、gRPC 和 JSON-RPC。
2. Scanner 只读取链数据，不构造、签名或广播交易。
3. SolidityNode 提供最新固化高度，FullNode 提供完整区块和交易回执。
4. `TronNodeClient` 完成单次请求；`TronNodePool` 和 `TronNodeManager` 统一完成选点与安全切换。
5. 节点状态只保存在内存，不增加节点状态表。

## 2. 节点职责

| 节点角色 | 职责 | 使用限制 |
|---|---|---|
| FullNode 主节点 | 按高度读取完整区块、交易和回执 | 只能读取 SolidityNode 已固化高度以内的区块 |
| FullNode 备用节点 | 主节点失败或落后时整组重读同一区块 | 不得把不同节点的区块与回执混合 |
| SolidityNode | 返回 TRON 最新固化高度 | 只确定扫描上界，不提供完整业务解析数据 |

### 2.1 最终充值边界

```text
Scanner查询SolidityNode最新固化高度S
→ 通过FullNode顺序读取检查点到S之间的完整区块
→ 解析TRX / TRC20充值
→ 发送DepositConfirmedEvent
→ wallet-chain-server幂等创建已确认充值并累计归集
```

Scanner 发出的是最终充值事件。Chain Server 不再保存 `CONFIRMING/ORPHANED` 中间状态，也不再重复查询 SolidityNode 确认。

## 3. 使用的TRON HTTP接口

### 3.1 FullNode最新Head

```http
POST /wallet/getblock
Content-Type: application/json

{"detail":false}
```

用于节点健康和落后判断，不决定充值扫描边界。

### 3.2 FullNode按高度读取完整区块

```http
POST /wallet/getblockbynum
Content-Type: application/json

{"num":61815425}
```

读取区块头和交易列表。

### 3.3 SolidityNode最新固化高度

```http
POST /walletsolidity/getblock
Content-Type: application/json

{"detail":false}
```

返回本轮扫描允许到达的最高固化区块。

### 3.4 FullNode按高度读取交易回执

```http
POST /wallet/gettransactioninfobyblocknum
Content-Type: application/json

{"num":61815425}
```

TRC20 `Transfer` 日志位于回执中，不能只读取交易体。

### 3.5 区块头与网络校验

按高度读取区块头用于：

- 通过创世区块 ID 确认节点网络。
- 每轮复核数据库检查点 Hash。
- 完整区块和回执读完后再次确认区块 Hash 没有变化。

## 4. 请求约束

1. 节点基础地址不携带业务路径，路径由 SDK 固定。
2. POST 使用 `application/json`，配置 API Key 时使用 `TRON-PRO-API-KEY` 请求头。
3. API Key 不进入 URL、异常信息、日志和指标标签。
4. 连接超时默认 `3s`，完整响应超时默认 `10s`，响应体默认上限 `16 MiB`。
5. Client 单次调用不隐式重试，避免与节点池切换叠加。
6. 一个区块的区块体、回执和最后 Hash 复核必须固定在同一个 FullNode 完成；任一步失败时由节点池在备用节点上整组重读。

## 5. Scanner内部入口

```java
TronNodeHeight getHeadHeight();

TronNodeHeight getSolidHeight();

TronNodeHeight getBlockHeaderByHeight(long blockHeight);

TronBlockData getBlockDataByHeight(long blockHeight);
```

调用方不传 `nodeCode`，不感知节点 URL 或主备关系。

### 5.1 `TronNodeHeight`

| 字段 | 说明 |
|---|---|
| `nodeCode` | 实际响应节点编码 |
| `blockHeight` | 区块高度 |
| `blockId` | 区块 ID |
| `blockTimestamp` | 链上区块时间 |

### 5.2 `TronBlockData`

| 字段 | 说明 |
|---|---|
| `nodeCode` | 实际读取节点编码 |
| `blockHeight` | 区块高度 |
| `blockId` | 当前区块 ID |
| `parentBlockId` | 父区块 ID，用于连续性校验 |
| `blockTimestamp` | 链上区块时间 |
| `transactions` | 完整交易列表 |
| `receipts` | 按 txId 组织的交易回执 |

## 6. 响应校验

节点调用同时满足以下条件才算成功：

1. HTTP 状态码为 2xx。
2. 响应体不为空，且不存在 TRON `Error` 字段。
3. 区块 ID、高度和时间存在且格式正确。
4. 按高度读取时，响应高度与请求高度一致。
5. 回执区块高度与请求高度一致，txId 不重复。
6. 启动校验时创世区块 ID 与当前环境一致。

HTTP 200 中也可能携带 TRON 错误，因此不能只判断 HTTP 状态码。

## 7. 错误与切换

| 错误 | 是否切换节点 | 处理 |
|---|---|---|
| 连接失败、超时、限流、5xx | 是 | 在备用节点上完整重读 |
| JSON或协议响应错误 | 是 | 标记当前节点失败并完整重读 |
| 节点网络不一致 | 否 | 永久隔离错误节点 |
| 请求高度不存在或节点未同步 | 是 | 选择已同步到目标高度的节点 |
| 所有节点不可用 | 否 | 当前轮失败，检查点不变 |
| 固化检查点Hash或父Hash不一致 | 否 | ERROR 916003，停止并人工核查 |

节点故障只影响当前轮，不允许返回伪造高度、空区块或提前推进检查点。

## 8. 配置边界

```yaml
nb:
  tron:
    scanner:
      chain-network: ${TRON_NETWORK:MAINNET}
      expected-genesis-block-id: ${TRON_GENESIS_BLOCK_ID}
      start-block-height: ${TRON_SCAN_START_BLOCK_HEIGHT}
      max-blocks-per-run: 100
      node:
        connect-timeout: 3s
        read-timeout: 10s
        max-response-size: 16MB
        health-check-interval: 15s
        failure-threshold: 3
        height-lag-threshold: 20
        recovery-cooldown: 60s
        nodes:
          - code: full-primary
            role: FULL_NODE
            priority: 1
            base-url: ${TRON_FULL_NODE_PRIMARY_URL:}
            api-key: ${TRON_FULL_NODE_PRIMARY_API_KEY:}
          - code: full-backup
            role: FULL_NODE
            priority: 2
            base-url: ${TRON_FULL_NODE_BACKUP_URL:}
            api-key: ${TRON_FULL_NODE_BACKUP_API_KEY:}
          - code: solidity-primary
            role: SOLIDITY_NODE
            priority: 1
            base-url: ${TRON_SOLIDITY_NODE_URL:}
            api-key: ${TRON_SOLIDITY_NODE_API_KEY:}
```

- FullNode 和 SolidityNode 都是启动必需配置，并且必须通过同一创世区块校验。
- `start-block-height` 是首次扫描的第一个高度；初始化检查点保存其前一块。
- `max-blocks-per-run` 限制单轮工作量，不改变固化边界。
- 节点地址、API Key 和创世区块 ID 由 Nacos 或环境变量提供。
- Scanner 不接收私钥、助记词、`key_ref` 或任何签名材料。

## 9. 代码职责

1. `TronNodeClient`：一次 HTTP 调用、反序列化和协议校验。
2. `TronNodePool`：节点健康、排序、失败冷却和备用选择。
3. `TronBlockGateway`：在同一 FullNode 上读取区块、回执并复核 Hash。
4. `TronNodeManager`：向 Scanner 暴露稳定节点能力并记录读取统计。
5. `HeadBlockContinuityService`：校验数据库检查点和固化区块连续性。

## 10. 官方依据

- [FullNode HTTP API](https://developers.tron.network/reference/full-node-api-overview)
- [FullNode GetBlock](https://developers.tron.network/reference/getblock-1)
- [FullNode GetBlockByNum](https://developers.tron.network/reference/wallet-getblockbynum)
- [FullNode GetTransactionInfoByBlockNum](https://developers.tron.network/reference/gettransactioninfobyblocknum)
- [SolidityNode GetBlock](https://developers.tron.network/reference/getblock-2)
- [TRON Exchange Wallet Integration](https://developers.tron.network/docs/exchangewallet-integrate-with-the-tron-network)
