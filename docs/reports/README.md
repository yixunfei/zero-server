# 历史报告

报告按日期记录一次审查或基准的范围、环境和结论。它们用于追溯决策，不能替代当前源码、测试和[能力矩阵](../capability-matrix.zh-CN.md)。

| 报告 | 用途 |
| --- | --- |
| [文档现状核对（2026-09-18）](documentation-audit-20260918.zh-CN.md) | 当前源码/文档核对、验证结果与 net 生成编译限制 |
| [缺陷核验与修复（2026-09-17）](bug-analysis-verification-20260917.zh-CN.md) | 原报告逐项纠正、回归测试和 0.x 行为调整；历史工作区证据 |
| [场景接入审查（2026-09-14）](scenario-audit-2026-09-14.zh-CN.md) | 三种服务器形态、依赖裁剪、已修复问题和后续路线 |
| [按需组装审查（2026-09-07）](composition-review-2026-09-07.zh-CN.md) | 早期问题发现与修复过程 |
| [按需组装推进记录（2026-09-12）](demand-composition-2026-09-12.zh-CN.md) | 实施批次和验证记录 |
| [协议编解码对比](protocol-codec-comparison.zh-CN.md) | Zero Binary Protocol、Protobuf、FlatBuffers 的基准方法 |

新报告应注明日期、提交、JDK/Maven、命令、结果和未验证项；当前入口或能力状态变化应同步更新 `docs/` 主题文档，而不是只修改历史报告。
