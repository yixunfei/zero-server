# 2026-10-05 全仓可靠性复核迁移说明

本说明适用于当前 `0.1.2-SNAPSHOT` 工作树，记录本轮在 `zero-monitor` 的 0.x 行为变化。无构造器、SPI、协议或持久化格式修改。

## 本地告警

过去首次 `AlertSink.publish` 抛错也会把告警标为已发布；条件持续满足时，该事件不会再尝试。现在仅在全部 sink 返回成功后标记。失败向评估调用方抛出，下一次评估在条件仍满足时重试整条事件。前序 sink 已接收后、后序 sink 抛错，或 sink 实际投递成功后抛错，都可能导致重复投递。依赖至多一次投递的接入方应检查自己的去重策略；框架不强制绑定单一 sink 或外部幂等存储。

## Prometheus 抓取

过去 `PrometheusExporter` 会把同一指标名、同一标签组合的历史样本在一次抓取中全部输出。现在按样本时间选择最新值，每个序列只输出一条；时间相同则选较晚记录。`PrometheusHttpEndpoint` 和 `MonitorRuntime.exportPrometheus()` 共享这一行为。`InMemoryMetricRegistry.samples()` 与本地 `exportText()` 的历史仍保留。依赖 Prometheus 文本读取历史的消费者应改用注册表快照或专门的历史存储。

## 门禁解释

公共 API 符号检查忽略 `javap` 输出中的方法级 `synchronized` 修饰符。该修饰符不属于 Java 二进制方法描述符；线程安全和锁范围变化仍需单独审查。受保护 API 基线未因此批量重新生成。

验证范围与剩余边界见[本轮报告](../reports/full-reliability-review-20261005.zh-CN.md)。
