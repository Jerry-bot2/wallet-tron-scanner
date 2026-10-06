# wallet-tron-scanner

TRON 独立扫描器。负责同步平台监控地址，后续负责 TRON 扫块、TRX/TRC20 交易解析和链上交易上报。

## 项目结构

```text
wallet-tron-scanner
├── client   链服务和 TRON 节点客户端
├── config   TRON 网络与同步配置
├── entity   扫描器本地数据库实体
├── job      地址同步和后续扫块任务入口
├── mapper   MyBatis Mapper
├── model    扫描器内部模型
└── service  数据库能力、地址同步和后续扫块流程
```

扫描器采用单模块工程，不包含 `client/common/biz/service/boss` 多模块。它不对外提供业务能力，也不保存私钥和客户账户数据。
扫描器使用独立 schema 保存监控地址本地索引和扫描检查点，运行时通过内存地址集合完成快速匹配。

## 当前阶段

扫描器基础表、区块摘要和持久化能力和 `chain-client` 契约已经就绪。下一步实现链服务接口和扫描器同步流程：

```text
从 wallet-chain-server 增量拉取地址
→ 保存到 scanner 本地数据库
→ 加入内存地址索引
→ 以内存已加载的最大 sourceAddressId 作为应用水位
→ 回报已经应用的最大 addressId
```

首期由 scanner 扫描 Head 区块及时发现充值，由 `wallet-chain-server` 定时读取固化高度并确认充值。完整设计见 [TRON 扫描器详细设计](docs/TRON扫描器详细设计.md)。

地址从 `PENDING_MONITOR` 到 `ACTIVE` 的完整过程见 [地址同步闭环图解](docs/学习笔记/01-地址同步闭环图解.md)。

## 本地数据库

默认连接本机 MySQL 的 `wallet_tron_scanner` 数据库，可以通过环境变量覆盖：

```bash
MYSQL_URL='jdbc:mysql://127.0.0.1:3306/wallet_tron_scanner?useUnicode=true&characterEncoding=utf8&serverTimezone=UTC'
MYSQL_USERNAME=root
MYSQL_PASSWORD=
```

首次运行前执行 [建表脚本](sql/wallet-tron-scanner-ddl.sql)。

## 本地构建

项目默认从 Maven Local 读取 `nb-common` 和 `chain-client`。首次构建前，在本机发布一次：

```bash
cd ../../uuwallet/nb-common
./gradlew publishToMavenLocal

cd ../../mypay/wallet-chain-server
./gradlew :chain-client:publishToMavenLocal
```

然后执行：

```bash
./gradlew clean build
```

本地默认关闭 XXL-JOB。需要接入调度中心时设置：

```bash
XXL_JOB_ENABLED=true
XXL_JOB_ADMIN_ADDRESSES=http://xxl-job-admin:8080/xxl-job-admin
```

需要同时修改 `nb-common` 源码时，可以启用 Composite Build：

```bash
./gradlew clean build -PuseLocalNbCommon=true
```

1.持久化基础：已完成
2.链服务同步契约：已完成
3.地址同步闭环：已完成
4.TRON 节点能力 已完成（[图解](docs/学习笔记/02-TRON节点能力图解.md)）

5.交易解析 已完成（[图解](docs/学习笔记/03-TRON交易解析图解.md)）

6.充值发现闭环 已完成（[设计](docs/阶段6-充值发现闭环设计.md)、[图解](docs/学习笔记/04-充值发现闭环与分叉恢复图解.md)）

6.1 定义充值发现契约 完成
- 使用 `ObservedBlockEvent` 按区块发送充值事实。
- 区块公共字段只保存一份，单条 `DepositDiscoveryEvent` 保存交易事件字段。
- Topic 为 `wallet.chain.deposit.discovered`，同一链网络使用固定消息 Key 保证顺序。

6.2 链服务幂等接收 完成
- 校验币种、充值地址和当前运行网络。
- 通过 `chainCode + chainNetwork + txId + eventIndex` 幂等创建 `chain_deposit`。

6.3 Scanner 区块处理编排 完成
- 读取下一个区块，解析并发送全部充值事实。
- Kafka Broker ACK 成功后推进 `HEAD_BLOCK` 检查点。

6.4 失败重试 完成
- 节点读取、解析或 Kafka 发送失败时不推进检查点。
- 下一轮继续处理同一高度，依靠链服务幂等安全重发。

6.5 区块连续性检查 完成
- 每轮核对已扫描末块 Hash，解析前检查下一块的父 Hash。
- 读取回执后复核区块 Hash，发生变化就丢弃本次数据，整块重读。
- 下一块接不上但末块仍正确时，冷却返回异常数据的节点；分叉成功回退时正常结束任务，下轮重扫。
- 新增 `tron_scanned_block`，摘要与检查点在一个手动事务中提交。
- 分叉时固定一个 FullNode，二分查找最近共同区块，事务回退后重扫。
- 默认连续保留最近 2 万条摘要；找不到共同区块时明确报错，保持扫描进度。充值确认由 chain-server 完成。
- 旧摘要按高度整百清理，如 20000、20100；普通区块只保存摘要、更新进度。两次清理之间最多暂时多保留 99 条连续摘要，无需增加配置。
- 使用当前完整 [建表 SQL](sql/wallet-tron-scanner-ddl.sql)。

6.6 样本测试与图解 完成
- 验证空区块、单笔和多笔充值、安全重发、发送失败及重启恢复。
- 穷举 1000 条历史内全部 999 个分叉边界，补充轮内分叉、连续分叉、回退后重扫，以及超出保留窗口时保持进度、报错而不跳块的测试。
- 提供真实 Nile 节点验收测试，覆盖区块/回执读取，以及注入 1/4/8 块旧分支 Hash 后的查找、回退和重扫；需要配置节点后执行。
- 本轮完整测试 183 项通过，0 失败；3 项真实测试网验收因未配置节点而跳过。覆盖正常扫块、失败重试、窗口内回退、窗口外保持进度、重启重扫和事务回滚。图解、验收数据和复跑命令见[阶段 6 设计第 10 节](docs/阶段6-充值发现闭环设计.md#10-66-样本测试与图解)。

7.固化确认闭环
8.生产保障


地址 -> 这个目前流程都还没有完
充值 -> 没有开始
提现 -> 没有开始
归集 -> 没有开始
签名服务 -> 没有开始

### 6.5 代码阅读顺序

前提：一个 Scanner 实例，`HeadBlockScanJob` 配置为单机串行，只配置一个扫块任务。

```text
加载扫描进度
→ 比较末块 Hash
  ├─ 不同：找共同区块 → 事务回退 → 结束本轮；找不到则报错，进度不变
  └─ 相同：顺序读取、解析、发送 → ACK 后提交进度
```

| 类 | 负责什么 |
|---|---|
| `HeadBlockScanService` | `scanBlocks` 汇总一轮，`scanRound` 分流，`scanNewBlocks` 顺序扫块，`processBlock` 解析、发送、提交 |
| `HeadBlockContinuityService` | 比较末块和父 Hash、查找共同区块并调用回退 |
| `HeadBlockAncestorFinder` | 固定 FullNode，二分查找并复核近期共同区块 |
| `HeadScanProgressService` | 初始化、提交进度、清理摘要、事务回退 |
| `HeadScanStatistics` | 统一管理一轮统计作用域，汇总完成块数、落后高度和各入口耗时 |

`checkCheckpoint()` 返回 `HeadBlockCheckResult`，入口通过 `checkResult.fork()` 明确判断是否进入分叉处理。

`rewind()` 只有两步 SQL：更新检查点 → 删除共同区块之后的摘要。
例如 1000 退到 998：保留 998，删除 999、1000，下轮从 999 重扫；任一步失败全部回滚。

摘要默认保留最近 2 万条，每 100 个高度清理一次；期间最多多留 99 条。

### 扫块性能观察

1. 只查区块头时使用 `/wallet/getblock` 的 `detail=false`；可选固化节点使用 `/walletsolidity/getblock`。完整区块和回执仍分别读取，第三次 Hash 复核只下载区块头。
2. 每轮结束输出一条 `TRON Head扫描本轮结束` 日志，成功、失败和分叉轮次都会记录。
3. 统计复用本轮已读到的高度，用 `System.nanoTime()` 计算耗时，无需新增配置。

扫块入口只调用一次 `HeadScanStatistics.recordRound(...)`，`scanRound`、`scanNewBlocks` 和 `processBlock` 保持业务编排，不传递统计参数。HTTP 请求、Kafka 发布和进度提交各自在自己的入口记录；本轮结束时统一汇总并清理线程内的统计作用域。其他线程的健康检查不计入扫块统计。

| 日志字段 | 含义 |
|---|---|
| `completed` | 本轮是否正常返回；异常时为 false，原异常继续向外抛出 |
| `forkRecheck` | 本轮是否进入分叉复查；父 Hash 接不上也要复查，不代表已经确认分叉 |
| `startHeight` / `lastCompletedHeight` | 本轮开始前的位置 / 本轮最后成功提交的位置；分叉回退后的实际位置看回退日志 |
| `observedHeadHeight` | 本轮查询时节点的最新高度；不是日志输出时重新查询的高度 |
| `remainingBlocks` | 节点高度减去最后完成高度；尚未读取节点高度或进入分叉复查时为 null |
| `scannedCount` | 本轮实际成功提交的区块数；即使后续失败或回退，也保留此前完成数 |
| `elapsedMillis` / `averageBlockMillis` | 本轮总耗时 / 总耗时除以完成数；未完成区块时平均值为 null |
| `nodeReadMillis` | 本轮所有节点 HTTP 请求的累计耗时，包含响应读取、JSON 解析、失败请求、初始化和分叉查找 |
| `kafkaAckMillis` | Kafka 发送并等待 ACK 的累计耗时，失败和超时也计时 |
| `progressCommitMillis` | 正常扫块进度事务的累计耗时，包含摘要保存、进度更新和定期清理 |

例如本轮从 `1000` 提交到 `1100`，读取的节点高度为 `1200`：`scannedCount=100`，`remainingBlocks=100`。初次加载、交易解析和分叉处理计入总耗时，阶段耗时之和不要求等于总耗时。完成数只在进度事务成功后累加，事务回滚或 Kafka 失败不算完成。

本次优化验证：完整测试 190 项通过，0 失败，3 项需配置真实节点的验收测试跳过。覆盖统计线程隔离、失败后重试、分叉回退和实际完成数量。另外直接调用 Nile 公共节点验证最新区块头、指定高度区块头和最新固化区块头：均不返回交易列表，同高度 Hash 一致。实际扫块吞吐量仍需结合运行日志观察。
找不到共同区块时抛出 `HEAD_SCAN_COMMON_ANCESTOR_NOT_FOUND`，交给 XXL-JOB 按失败告警策略处理。
节点读取失败、高度不足不作为分叉；一次查找固定一个节点，读失败或分支变化就结束本轮重试。


正常扫描
1. 加载扫描进度，例如 1000/H1000。
2. 查询节点的 1000，Hash 相同就继续。
3. 读取 1001，检查父 Hash 是否等于 H1000。
4. 解析充值、发送 Kafka，收到 ACK 后，事务保存摘要和进度。
   扫描过程中下一块接不上，也进入同一个分叉复查流程。
   分叉恢复
   例如已扫到 1000，节点的 1000 Hash 变了：
1. 在保留摘要中找到最后相同的区块，例如 998。
2. 一个事务完成：进度退到 998＋删除 998 之后的摘要。
3. 结束本轮，下轮从 999 重扫。
