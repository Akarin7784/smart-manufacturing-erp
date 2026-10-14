# 集成接口错误码对照

过渡期 ERP 错误体保持 `R{code,msg,data}`：HTTP 状态 = 大类，`code` = 细分码。通用错误 `code` 等于 HTTP 状态；业务细分码 = HTTP × 100 + 序号（与 `ErrorCode` 枚举一致）。
`problem type` 列供 MES 适配层映射到它自己的 problem+json，ERP 现在不返回该字段。调用方不得解析 `msg`。

| code | HTTP | problem type | 含义 | MES 处理 | 状态 |
| --- | --- | --- | --- | --- | --- |
| 0 | 201 / 200 | — | 首次受理 / 幂等重放 | 完成 | 已有 |
| 400 | 400 | `validation-error` | 字段校验失败、引用（物料/仓库/生产订单）不存在、uom 不符、缺 `Idempotency-Key` | 不重试，转人工 | 已有 |
| 40002 | 400 | `idempotency-key-mismatch` | `Idempotency-Key` ≠ `requestNo` | 不重试（修代码） | 计划新增 |
| 401 | 401 | `authentication-required` | 令牌缺失 / 无效 / 过期 / `aud` 不符 | 刷新令牌后重试 1 次 | 已有 |
| 403 | 403 | `access-denied` | scope 不符，或用户令牌调用集成接口 | 不重试 | 已有 |
| 40900 | 409 | `concurrent-modification` | 并发冲突，内部重试用尽 | 退避重试 | 已有 |
| 40902 | 409 | `idempotency-key-conflict` | `requestNo` 已用于不同内容（或不同接口） | 不重试，转人工 | 已有 |
| 40904 | 409 | `idempotency-in-progress` | 同 `requestNo` 首次请求仍在处理 | 退避重试 | 已有 |
| 40905 | 409 | `erp-document-state-conflict` | 生产订单状态不允许（已关闭 / 取消等） | 不重试，转人工 | 计划新增 |
| 40906 | 409 | `material-shortage` | 库存不足 | 不重试，转人工 | 计划新增 |
| 500 | 500 | `internal-error` | 系统异常 | 退避重试 | 已有 |
| 50301 | 503 | `service-busy` | 取号繁忙；未落库、未占用 `requestNo` | 退避重试 | 已有 |

MES 侧 problem type `idempotency-key-conflict` 在 MES 自己的接口里是 422；ERP 对应为 409 / 40902。
网络错误、超时、429 一律退避重试；重试始终用同一个 `requestNo`。
"计划新增"的码在回调实现 PR 中加入 `ErrorCode` 枚举，码值以本表为准。
