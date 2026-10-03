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

5.交易解析 进行中

5.0 交易解析边界与模型 完成
- 冻结统一输入 `TronBlockData` 和输出 `TronDepositEvent`。
- 充值事实只保存链上原始数据，不换算展示金额，不表示已经固化到账。
- 使用 `chainCode + chainNetwork + txId + eventIndex` 作为稳定幂等键。
- TRX 使用负数事件序号，TRC20 使用日志序号，避免同一交易内冲突。
- 地址统一转换为 Base58Check，原始金额使用 `BigInteger`。
- 解析失败时不返回部分结果，不推进扫描检查点。
- 详细设计见 [阶段5：TRON交易解析设计](docs/阶段5-TRON交易解析设计.md)。

5.1 币种配置同步与内存快照 完成
- 启动时从链服务拉取当前网络的完整币种配置，首次加载失败则拒绝启动。
- 按原生币和 TRC20 合约地址构建只读内存索引，供后续交易解析直接查询。
- 新配置全部校验成功后原子替换快照，定时刷新失败时继续使用上一版配置。
5.2 TRON 地址统一转换 完成
- 节点 Hex 地址、TRC20 地址 Topic 统一转换为 Base58Check。
- 严格校验地址长度、TRON 网络前缀、Topic 补位和 Base58Check 校验和。
- 后续解析器只使用 Base58Check 地址匹配平台地址索引。
5.3 TRX 转账解析器
5.4 TRC20 Transfer 解析器
5.5 统一区块解析入口
5.6 交易解析样本测试与验收

6.充值发现闭环
7.固化确认闭环
8.生产保障


地址 -> 这个目前流程都还没有完
充值 -> 没有开始
提现 -> 没有开始
归集 -> 没有开始
签名服务 -> 没有开始
