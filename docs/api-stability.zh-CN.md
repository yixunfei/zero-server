# Public API baseline stability

P0-5 bootstrap baseline 使用以下稳定性分层：

- `STABLE`：已纳入 baseline 的公共 core/runtime/protocol/data contract；删除或签名变化必须有迁移说明。
- `INCUBATING`：新增但仍可能在 0.x 调整的公共扩展点；仍记录 surface，变更必须经过兼容门禁。
- `EXPERIMENTAL`：明确标注为实验性的新能力；不得绕过门禁。
- `DEPRECATED`：保留兼容调用面但要求迁移到 replacement；删除必须经过版本迁移说明。

当前 baseline 是 `0.1.0-SNAPSHOT` 的 bootstrap，不是历史发布版本，也不构成完整二进制兼容承诺。
