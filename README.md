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
4.TRON 节点能力 进行中
目标：
- 为后续 Head 扫块提供统一、稳定的 TRON 节点访问入口。
- 支持 Head 高度、固化高度，以及按高度读取区块和交易回执。
- 支持节点健康检查、主备选择和故障切换。
- 本阶段不解析交易、不推进扫块检查点、不上报充值。

4.0 节点边界与接口冻结 完成
- 首期使用 TRON HTTP API，不引入 Trident SDK。
- FullNode：查询最新 Head 高度、按高度读取区块。
- FullNode：按区块高度读取交易回执，供后续 TRC20 日志解析。
- SolidityNode：查询最新固化高度，只用于确定 Scanner 的未固化区块重扫范围。
- Scanner 只上报发现事实；`wallet-chain-server` 独立核验固化交易并推进充值状态。
- 节点地址和 API Key 只放在 Nacos 或环境变量。
- Scanner 不保存节点密钥、私钥和签名材料。
- 冻结节点超时、错误分类和返回模型。
- 明确主节点、备用节点和 SolidityNode 的职责。
- 详细契约见 [阶段4：TRON节点能力契约](docs/阶段4-TRON节点能力契约.md)。

4.1 节点配置模型 完成
- 在 TronScannerProperties 中增加节点配置。
- 每个节点包含 code、role、priority、baseUrl 和可选 apiKey。
- 增加当前网络创世区块 ID，用于启动时校验节点网络。
- 增加 `startBlockHeight`，表示首次启动时第一个需要扫描的区块。
- 增加 `recheckWindow`，SolidityNode 不可用时默认重扫最近 100 个 Head 区块。
- 增加连接超时、读取超时、健康刷新间隔、连续失败阈值和高度落后阈值。
- 增加可配置的响应体上限，默认 16 MiB。
- 节点 code 必须唯一。
- 节点客户端启动后固定创建，首期不做运行时动态重建。
- 不增加节点数据库表。

4.2 TRON HTTP 客户端 完成
- 新增 TronNodeClient，封装 TRON HTTP 请求。
- 支持查询 FullNode 最新 Head 高度。
- 支持查询 SolidityNode 最新固化高度。
- 支持按区块高度读取完整区块。
- 支持按区块高度读取交易回执。
- TRON 原始响应 DTO 只保留在 client 包内。
- 对外返回 Scanner 自己的节点高度和区块模型。
- 统一处理超时、连接失败、限流和非法响应。
- 不在 Client 中处理节点选择和业务重试。

4.3 节点启动校验 完成
- 启动时逐个检查节点是否可以访问。
- 校验节点属于当前配置的 TRON 网络。
- 校验节点返回的区块高度和区块头结构合法。
- 网络不一致的节点禁止进入可用节点集合。
- 没有可用 FullNode 时 Scanner 不允许进入扫块就绪状态。
- 没有可用 SolidityNode 时明确记录固化能力不可用。
- SolidityNode 暂时不可用不阻断 Head 发现；后续扫块编排改用固定 `recheckWindow`。

4.4 节点运行状态
- 在内存中记录节点健康状态，不写数据库。
- 记录最新高度、连续失败次数、最近响应耗时和最近成功时间。
- 新增 TronNodeHealthService，由每个 Scanner 实例本地定时刷新节点状态。
- 节点状态保存在本机内存，因此不使用 XXL-JOB 的单实例路由执行健康刷新。
- 健康检查异常原样记录，不影响地址同步任务。
- 节点状态只描述运行情况，不承载扫块业务逻辑。

4.5 主备节点选择与切换
- 新增 TronNodeManager，统一提供当前可用节点。
- 正常情况下持续使用当前主节点，不按请求随机选择。
- 当前请求出现连接、超时、限流或服务端错误时，允许使用一个合格备用节点完成一次安全降级。
- 主节点连续失败达到阈值后标记为不健康，后续请求直接使用备用节点。
- 主节点连续多次明显落后于健康节点的最高高度时，标记为不健康。
- 原主节点恢复后不立即切回，避免节点反复抖动。
- 所有 FullNode 都不可用时明确失败，不返回伪造高度或空区块。
- 按高度读取区块时，只选择已同步到该高度的节点。

4.6 统一节点读取入口
- 对后续业务只提供统一的 TronNodeManager。
- 业务层不直接指定主节点或备用节点。
- 提供查询最新 Head 高度的方法。
- 提供查询最新固化高度的方法。
- 提供按高度读取区块数据的方法，在同一 FullNode 上读取区块和对应交易回执。
- 节点调用失败时，由 Manager 完成一次安全切换。
- 切换后仍失败则终止本次调用，交给下一调度周期重试。

4.7 节点能力测试与验收
- 验证主节点正常时不会随机切换。
- 验证主节点超时后可以切换备用节点。
- 验证高度落后的节点不会被选中。
- 验证错误网络的节点启动时被拒绝。
- 验证节点恢复后不会立即抢回主节点。
- 验证所有节点不可用时返回明确错误。
- 验证固化高度与 Head 高度是两种不同语义，不要求两者相等，也不以高度差单独判定节点故障。
- 当健康 FullNode 已超过固化高度时，验证两种视图在该固化高度上返回相同 `blockId`。
- 验证按高度读取时，返回区块高度、回执区块高度与请求高度一致。
- 验证相邻区块的 `parentBlockId` 与上一区块 `blockId` 一致。
- 验证 SolidityNode 不可用时不伪造固化高度，并能降级为固定窗口重扫。
- 使用真实测试网节点完成 Local/Test 环境验收。

阶段4完成标准：
- 后续扫块代码只依赖 TronNodeManager。
- 业务代码不感知节点 URL、API Key 和主备关系。
- 任一主节点不可用时可以安全切换备用节点。
- 节点全部不可用时不推进任何扫块水位。
- 节点能力测试全部通过。


5.交易解析
6.充值发现闭环
7.固化确认闭环
8.生产保障


地址 -> 这个目前流程都还没有完
充值 -> 没有开始
提现 -> 没有开始
归集 -> 没有开始
签名服务 -> 没有开始
