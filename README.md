# wallet-tron-scanner

TRON 独立扫描器。负责同步平台监控地址，后续负责 TRON 扫块、TRX/TRC20 交易解析和链上交易上报。

## 项目结构

```text
wallet-tron-scanner
├── client   链服务和 TRON 节点客户端
├── config   TRON 网络与同步配置
├── job      地址同步和后续扫块任务入口
├── model    扫描器内部模型
└── service  地址同步、内存快照和后续扫块流程
```

扫描器采用单模块工程，不包含 `client/common/biz/service/boss` 多模块。它不对外提供业务能力，也不保存私钥和客户账户数据。
地址同步阶段不使用数据库，扫描游标和区块检查点的持久化方案在扫块阶段确定。

## 当前阶段

当前只创建工程骨架。下一步先完成地址同步闭环：

```text
从 wallet-chain-server 增量拉取地址
→ 校验并合并到内存地址快照
→ 原子替换当前快照
→ 回报已经应用的最大 addressId
```

真正扫块、充值识别和区块检查点将在“扫块与充值闭环”阶段实现。

## 本地构建

项目默认从 Maven Local 读取 `nb-common` 组件。首次构建前，在本机发布一次：

```bash
cd ../../uuwallet/nb-common
./gradlew publishToMavenLocal
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
