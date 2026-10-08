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

7.固化确认闭环 （争取今天完成）
8.生产保障


地址 -> 这个目前流程都还没有完
充值 -> 没有开始
提现 -> 没有开始
归集 -> 没有开始
签名服务 -> 没有开始


采用最新方案：充值表保存状态版本和通知标记，一个任务通知最新状态，Kafka ACK 按版本标记成功。
下面可以直接复制到 README：
7. 固化确认闭环
- 7.1 固化查询契约与环境配置：定义确认适配器、核验结果和节点配置。完成
- 7.2 数据库模型与查询：补充状态版本、通知状态、枚举、索引和条件更新。 完成
- 7.3 TRON 固化核验：实现节点客户端、网络校验和交易有效性核验。处理中 -> 发现需要抽取很多TRON重复的代码
- 7.4 正常确认任务：分页查询待确认充值，核验后推进状态和版本。
- 7.5 充值发现与分叉恢复：处理重复发现、重新入块、充值单修正和失效复查。
- 7.6 统一状态通知：定义共享消息，实现发送重试、ACK 版本保护和下游幂等约定。
- 7.7 闭环测试与图解：验证重复、乱序、并发和故障恢复，补充完整流程图。
