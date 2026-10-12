# 多服务器玩家示例迁移说明

后续审核已修正示例实现和验证边界；当前用法、示例 API/schema 迁移及最新证据见 [examples 审核修复](20261012-examples-review.zh-CN.md)。下文保留首次新增时的记录。

## 变更

新增 `examples/multi-server-player`，提供注册、登录、创建角色、断线重连、登录服/中心服/逻辑服职责划分，以及 Kafka、Nacos、MongoDB、Redis 的外部组合验收。

## 影响边界

本次只新增示例、测试、Compose 和文档，不修改生产模块公共 API、协议格式或依赖方向。示例的本地测试使用内存替身；外部验收使用独立 Compose 项目和端口。

## 接入步骤

1. 先执行 `mvn -q -f examples/multi-server-player/pom.xml clean verify` 验证本地业务不变量。
2. 执行 `examples/multi-server-player/scripts/verify-external.ps1` 验证四个外部组件和 Adapter 边界。
3. 复制 `PlayerLifecycleService` 的 Repository/Cache 注入方式到业务项目，再根据部署环境设置 `ZERO_*` 连接变量。

## 限制

示例不提供真实密码校验、Token 签发、跨进程故障转移或容量保证。Kafka 重复投递、MongoDB 与 Redis 的一致性、服务发现抖动必须由正式业务继续实现幂等键、唯一索引、版本条件写和超时策略。
