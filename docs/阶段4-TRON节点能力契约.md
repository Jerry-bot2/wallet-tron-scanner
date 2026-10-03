# 阶段4：TRON 节点能力契约

本文冻结 `wallet-tron-scanner` 访问 TRON 节点的边界。阶段 4.1～4.7 必须遵守本文，不在业务代码中自行拼接节点 URL、判断主备节点或解释节点异常。

## 1. 技术决策

1. 首期使用 java-tron 原生 HTTP API，不引入 Trident、gRPC 和 JSON-RPC。
2. Scanner 只使用节点读取能力，不构造、签名或广播链上交易。
3. 节点原始响应只存在于 `client.tron` 包，后续扫块和解析代码只依赖 Scanner 内部模型。
4. 节点客户端不做隐式重试；主备切换由 `TronNodeManager` 统一控制。
5. 节点状态只保存在内存，不增加数据库表。

选择 HTTP API 的原因是当前阶段只需要三个读取能力，继续引入 Trident 会额外带入 gRPC、Protobuf、签名和交易构造能力，扩大依赖面但不能改善当前业务闭环。

## 2. 节点职责

| 节点角色 | 职责 | 禁止事项 |
|---|---|---|
| FullNode 主节点 | 查询最新 Head 高度；按高度读取完整区块；作为正常扫块数据源 | 不作为最终到账依据 |
| FullNode 备用节点 | 主节点不可用或明显落后时接管 Head 查询和区块读取 | 主节点正常时不得随机分流；按高度读取时，未同步到请求高度的节点不得接管 |
| SolidityNode | 查询最新固化高度，用于确定 Scanner 重扫未固化区块的起点 | 不替代 FullNode 扫描最新 Head；不在 Scanner 中确认充值到账 |

SolidityNode 与 FullNode 虽然读取同一条链，但服务高度语义不同，不能互相作为无条件备用节点。`wallet-chain-server` 会独立读取 SolidityNode 核验充值是否最终固化；Scanner 只使用固化高度缩小未固化区块重扫范围，不推进业务状态。

### 2.1 最终责任边界

```text
Scanner 读取 FullNode Head
→ 读取同一 FullNode 的区块和交易回执
→ 解析 TRX / TRC20 充值
→ 上报 wallet-chain-server
→ wallet-chain-server 幂等创建 CONFIRMING 充值记录
→ wallet-chain-server 独立通过 SolidityNode 核验固化交易和回执
→ CONFIRMING 推进为 CONFIRMED 或 ORPHANED
```

- Scanner 负责及时发现和 Head 分叉重扫，不判定充值最终到账。
- `wallet-chain-server` 负责保存链事实、核验固化结果和推进充值状态。
- 两个服务可以独立查询固化高度，但用途不同，不在服务之间传递固化高度。

## 3. 冻结的 TRON HTTP 接口

### 3.1 FullNode 最新 Head

```http
POST /wallet/getnowblock
Content-Type: application/json

{}
```

用途：取得节点当前最新 Head 区块头。该区块可能尚未固化。

### 3.2 FullNode 按高度读取区块

```http
POST /wallet/getblockbynum
Content-Type: application/json

{"num": 61815425}
```

用途：按确定高度读取完整区块和交易列表，供后续 Head 顺序扫描。

### 3.3 SolidityNode 最新固化高度

```http
POST /walletsolidity/getnowblock
Content-Type: application/json

{}
```

用途：取得 SolidityNode 当前最新固化区块头。固化高度正常情况下落后 Head，不以两者存在高度差直接判定节点异常。

### 3.4 FullNode 按区块高度读取交易回执

```http
POST /wallet/gettransactioninfobyblocknum
Content-Type: application/json

{"num": 61815425}
```

用途：获取指定 Head 区块中全部交易的执行结果和日志。TRC20 `Transfer` 事件位于回执 `log` 中，仅读取 `/wallet/getblockbynum` 返回的交易体无法完成 TRC20 充值解析。

### 3.5 启动网络校验

启动时分别调用 FullNode 的 `POST /wallet/getblockbynum` 和 SolidityNode 的 `POST /walletsolidity/getblockbynum` 查询高度 `0`，将返回的 `blockID` 与当前环境配置的 `expectedGenesisBlockId` 比较。仅配置 `MAINNET`、`NILE` 等文本名称不能证明节点实际连接到了正确网络。

网络校验只在节点初始化阶段使用，不作为后续业务读取接口。

## 4. 请求约束

1. 节点基础地址不允许携带业务路径，路径由 Client 固定。
2. 所有请求显式声明接受 JSON；POST 请求使用 `application/json`。
3. 配置了 API Key 时统一使用 `TRON-PRO-API-KEY` 请求头；未配置时不发送空请求头。
4. API Key 不允许出现在 URL、异常信息、日志和指标标签中。
5. 连接超时默认 `3s`，响应超时默认 `10s`。
6. Client 单次调用不自动重试，避免一层 HTTP 重试和一层节点切换相互叠加。
7. 单次响应默认上限为 `16 MiB`，并允许通过配置调整；禁止无上限缓存响应正文。TRON 区块交易数据上限约为 `2 MB`，JSON 编码和交易回执会进一步放大响应，`4 MiB` 不适合作为生产硬上限。

超时值后续允许通过 Nacos 调整，但字段含义和默认值不再改变。

## 5. Scanner 内部接口

后续节点实现必须收敛到以下三个能力：

```java
TronNodeHeight getHeadHeight();

TronNodeHeight getSolidHeight();

TronBlockData getBlockDataByHeight(long blockHeight);
```

调用方不传 `nodeCode`，不感知主节点或备用节点。具体节点由 `TronNodeManager` 选择。

### 5.1 `TronNodeHeight`

| 字段 | 类型 | 说明 |
|---|---|---|
| `nodeCode` | `String` | 实际响应本次请求的节点编码 |
| `blockHeight` | `long` | 区块高度，必须大于等于 0 |
| `blockId` | `String` | 节点返回的区块 ID，不能为空 |
| `blockTimestamp` | `Instant` | 区块头时间，不使用 Scanner 本机时间代替 |

### 5.2 `TronBlockData`

| 字段 | 类型 | 说明 |
|---|---|---|
| `nodeCode` | `String` | 实际读取区块的节点编码 |
| `blockHeight` | `long` | 区块高度，必须与请求高度一致 |
| `blockId` | `String` | 当前区块 ID |
| `parentBlockId` | `String` | 父区块 ID，用于后续连续性校验 |
| `blockTimestamp` | `Instant` | 链上区块时间 |
| `transactions` | `List<TronTransaction>` | 区块内完整交易列表，空块返回空集合，不返回 `null` |
| `receipts` | `Map<String, TronTransactionReceipt>` | 按 `txId` 组织的交易回执，供 TRC20 事件和执行结果解析 |

`TronNodeManager` 必须在同一个 FullNode 上读取区块与对应回执，其中任意一步失败时，将整组读取切换到同一个备用节点重新执行，禁止把不同节点的未固化区块和回执混合。

`TronTransaction` 和 `TronTransactionReceipt` 的最小解析字段在阶段 5 冻结；阶段 4 必须保证交易体、执行结果和 `log` 不会在 Client 映射时丢失。

## 6. 响应校验

满足以下全部条件才算节点调用成功：

1. HTTP 状态码为 2xx。
2. 响应体不为空，且不存在 TRON `Error` 字段。
3. `blockID`、`block_header.raw_data.number` 和 `timestamp` 存在且格式合法。
4. 按高度查询时，返回高度与请求高度完全一致。
5. 回执中的 `blockNumber` 必须与请求高度一致，txId 不得重复。
6. 节点启动校验时，创世区块 ID 与 `expectedGenesisBlockId` 一致。

TRON 节点可能在 HTTP 200 响应中返回 `Error`，因此不能只根据 HTTP 状态码判断成功。

## 7. 错误分类

阶段 4 使用 `913xxx` 错误码段：

| 错误 | 预留错误码 | 含义 | 是否允许切换节点 |
|---|---:|---|---|
| `TRON_NODE_CONFIG_INVALID` | `913001` | 节点编码、角色、URL、超时或网络指纹配置错误 | 否，启动失败 |
| `TRON_NODE_CONNECT_FAILED` | `913002` | DNS、拒绝连接、TLS 或网络不可达 | 是 |
| `TRON_NODE_TIMEOUT` | `913003` | 连接或响应超时 | 是 |
| `TRON_NODE_RATE_LIMITED` | `913004` | HTTP 429/限流响应或节点资源不足 | 是 |
| `TRON_NODE_REMOTE_ERROR` | `913005` | 非 2xx 或响应体包含 `Error` | 5xx 允许；普通 4xx 不允许 |
| `TRON_NODE_RESPONSE_INVALID` | `913006` | JSON、区块头、高度或时间字段不合法 | 是，并记录节点异常 |
| `TRON_NODE_NETWORK_MISMATCH` | `913007` | 创世区块 ID 与当前环境不一致 | 否，节点永久隔离 |
| `TRON_BLOCK_NOT_FOUND` | `913008` | 指定高度不存在或节点尚未同步到该高度 | 可换节点，不推进游标 |
| `TRON_NODE_UNAVAILABLE` | `913009` | 所有符合条件的节点均不可用 | 否，本轮失败 |

业务层只处理稳定错误类型，不解析第三方异常文本。

## 8. 配置边界

阶段 4.1 按以下结构实现配置，字段名称在本阶段冻结：

```yaml
nb:
  tron:
    scanner:
      chain-network: ${TRON_NETWORK:MAINNET}
      expected-genesis-block-id: ${TRON_GENESIS_BLOCK_ID}
      start-block-height: ${TRON_SCAN_START_BLOCK_HEIGHT}
      recheck-window: 100
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

- `base-url`、API Key 和创世区块 ID 只能由 Nacos 或环境变量提供，不写入代码仓库。
- `start-block-height` 只在本地没有扫块检查点时使用，表示第一个需要扫描的区块高度；检查点存在后以数据库为准。
- `failure-threshold` 表示节点连续失败多少次后标记为不健康，默认 `3`。它不限制当前请求使用备用节点完成一次安全降级。
- `height-lag-threshold` 表示 FullNode 比本轮所有健康 FullNode 的最高高度落后多少个区块后视为明显落后，默认 `20`；单个节点不能凭自身高度判定是否落后。
- `recovery-cooldown` 表示原主节点恢复后至少稳定观察多久才允许重新成为主节点，默认 `60s`。
- `recheck-window` 表示 SolidityNode 暂时不可用时，Scanner 固定重扫最近多少个 Head 区块，默认 `100`。该降级只保持发现能力，不代替 chain-server 的固化确认。
- API Key 允许为空，以支持自建 java-tron 节点。
- API Key 属于访问凭证，但不是链上私钥；仍必须按敏感配置管理。
- Scanner 不接收和保存私钥、助记词、签名密钥、`key_ref` 或交易签名材料。

## 9. 本阶段明确不做

- 不实现 HTTP Client 和真实节点调用。
- 不实现节点健康状态、主备切换和恢复冷却。
- 不实现区块交易解析。
- 不推进 `HEAD_BLOCK` 检查点。
- 不创建充值记录或调用充值上报接口。
- 不动态刷新并重建节点客户端。

## 10. 后续实现约束

1. `TronNodeClient` 只负责一次 HTTP 调用、响应反序列化和协议校验。
2. `TronNodeManager` 只负责节点状态、选择和一次安全降级；按高度读取时只选择已同步到该高度的节点。
3. 业务代码只能调用 `TronNodeManager`，不能直接依赖 HTTP Client。
4. 本地健康调度和扫块 Job 只负责触发，不复制节点选择逻辑。
5. 节点全部不可用时必须失败关闭，不允许返回伪造高度、空区块或推进检查点。
6. SolidityNode 暂时不可用时，扫块编排按 `recheck-window` 重扫最近的 Head 区块；`TronNodeManager` 不伪造固化高度。

## 11. 官方依据

- [FullNode HTTP API](https://developers.tron.network/reference/full-node-api-overview)
- [FullNode GetNowBlock](https://developers.tron.network/reference/wallet-getnowblock)
- [FullNode GetBlockByNum](https://developers.tron.network/reference/wallet-getblockbynum)
- [FullNode GetTransactionInfoByBlockNum](https://developers.tron.network/reference/gettransactioninfobyblocknum)
- [SolidityNode GetNowBlock](https://developers.tron.network/reference/getnowblock)
- [SolidityNode GetBlockByNum](https://developers.tron.network/reference/getblockbynum)
- [TRON Blocks](https://developers.tron.network/docs/block)
- [Exchange Wallet Integration](https://developers.tron.network/docs/exchangewallet-integrate-with-the-tron-network)
