# 2026-10-12 业务开发纵向示例

后续审核已修正示例实现和验证边界；当前用法、示例 API/schema 迁移及最新证据见 [examples 审核修复](20261012-examples-review.zh-CN.md)。下文保留首次新增时的记录。

## 变更

- 新增 `examples/business-slice`，以每日道具领取为业务任务样例，串联协议生成、业务服务、Repository、缓存、内部事件、日志、指标和测试。
- 根 `examples/README.md` 增加业务开发入口；`zero-data`、`zero-cache`、`zero-event` 和 `zero-server-starter` 的模块示例链接到该纵向切片。
- 仅新增示例与文档，不改变 Java 公共 API、协议格式、运行时行为、依赖方向或数据格式。

## 使用与验证

```powershell
mvn -q -f examples/business-slice/pom.xml clean test
mvn -q -f examples/business-slice/pom.xml exec:java
```

执行结果应包含：

```text
business-slice=ok|statuses=[ACCEPTED, DUPLICATE]|inventory=100|events=1|logs=2|metrics=1|cacheHits=1|deadLetters=0
```

本示例默认不启动外部中间件。真实数据库、Redis、消息通道、鉴权、跨进程幂等和容量验证仍需应用在隔离环境中显式完成。

## 迁移说明

没有生产调用方迁移步骤；这是 0.x 阶段新增的可选示例入口。复制到业务工程时，应按示例 README 的替换清单接入事务/唯一键、玩家 Actor、鉴权、限流、审计和错误码。
