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

扫描器两张基础表、持久化能力和 `chain-client` 契约已经就绪。下一步实现链服务接口和扫描器同步流程：

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

6.充值发现闭环 处理中（[设计](docs/阶段6-充值发现闭环设计.md)）

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

6.5 区块连续性检查
- 推进高度前校验当前区块 `parentBlockId` 与上一检查点 `blockId`。
- 不一致时进入未固化分叉处理。

6.6 样本测试与图解
- 验证空区块、单笔和多笔充值、重复扫描、上报失败及重启恢复。

7.固化确认闭环
8.生产保障


地址 -> 这个目前流程都还没有完
充值 -> 没有开始
提现 -> 没有开始
归集 -> 没有开始
签名服务 -> 没有开始
