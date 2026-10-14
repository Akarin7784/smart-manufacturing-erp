# ERP ↔ MES 集成（M2）

> **本目录是 ERP↔MES M2 集成的唯一事实来源。** 双方不做端到端联调，按本契约实现，用 Mock 测试。
> 契约改动走 PR，由 PM 审查。当前只有契约，ERP 侧接口实现另开 PR。

| 文件 | 内容 |
| --- | --- |
| [`openapi.yaml`](openapi.yaml) | OpenAPI 3.1：三个 MES→ERP 回调（ERP 托管，权威），以及 ERP→MES 下达（MES 托管，调用方视角） |
| [`error-codes.md`](error-codes.md) | 数字码 ↔ problem type ↔ 含义 ↔ 重试策略 |

来源：MES 提案 [smart-manufacturing-cloud#86](https://github.com/Akarin7784/smart-manufacturing-cloud/pull/86)
（`docs/api/erp-integration-openapi.yaml`、`docs/erp-mes-integration-design.md`，head `4d88a0c`）。
路径和字段沿用该提案，与下面 PM 定稿冲突处以本目录为准。

## 0. PM 定稿（覆盖 MES 提案）

1. 本目录为唯一事实来源，路径/字段对齐 MES #86 + 以下决定。
2. MES→ERP 回调只用 ERP 自有集成服务账号（client_credentials），ERP 不校验 MES JWKS（§2）。
3. 三个回调：领料 / 完工入库 / 报废，按 `requestNo` 长期去重，不用 24h 用户维幂等。
4. 下达 `quantity` 为 6 位小数字符串；ERP 不传 `deviceId`（MES 侧改为可选）。
5. 错误体过渡期保持 `R{code,msg,data}`，给出数字码对照表（`error-codes.md`）。
6. 回调请求头 `Idempotency-Key` 必须等于 body `requestNo`；按服务身份去重；保留 ≥ 90 天。
7. 完工入库在工单 **COMPLETED** 时触发（不是 CLOSED）。
8. 领料为报工倒冲回传（报工成功后立即，一次报工一笔）；报废在工序上即时回传；退料须在工单 CLOSED 前。

## 1. 接口一览

| 方向 | 接口 | 托管方 | 触发 | 幂等 |
| --- | --- | --- | --- | --- |
| ERP→MES | `POST /api/integration/erp/work-orders/dispatch` | MES | ERP 生产订单审批通过 | `Idempotency-Key` = `erpProductionOrderNo`；同键不同请求体 → 409 / 40902 |
| MES→ERP | `POST /api/integration/mes/material-issues` | ERP | 报工倒冲回传（报工成功后立即） | `requestNo`（`MI-{工单号}-{工序}-{报工ID}`） |
| MES→ERP | `POST /api/integration/mes/finished-goods-receipts` | ERP | 工单 COMPLETED | `requestNo`（`FGR-{工单号}`，每工单一笔） |
| MES→ERP | `POST /api/integration/mes/scraps` | ERP | 工序报废即时 | `requestNo`（`SCR-{工单号}-{工序}-{报工ID或NCR号}`） |

回调成功：首次 **201**；重放 **200** + 响应头 `Idempotent-Replayed: true` + `data.replayed=true`，`erpDocumentNo` 与 `acceptedAt` 与首次相同。

## 2. 鉴权

### MES → ERP（回调）—— 唯一方案：ERP 自有集成服务账号（client_credentials）
- ERP 新增服务账号机制：`POST /api/auth/oauth2/token`，`grant_type=client_credentials`，`client_secret_basic`。
- MES 客户端 `mes-integration`；令牌为 ERP 签发的 JWT：`aud=erp-api`，`sub=svc:mes-integration`，
  `scope` ⊆ `erp:integration:material-issue erp:integration:finished-goods-receipt erp:integration:scrap`，有效期建议 ≤ 15 分钟。
- 选择理由：鉴权由 ERP 自己负责，服务身份（`sub`）直接作为幂等隔离维度；吊销/轮换不依赖 MES 可用性；ERP 已有 JWT 签发基础。
- 服务令牌不带用户权限：服务令牌调普通业务接口 → **403**；用户 JWT 调回调接口 → **403**（均为现有 `ErrorCode.FORBIDDEN`，code 403）。
- 错误：令牌缺失/无效/过期/`aud` 不符 → **401**（code 401）；scope 不符 → **403**（code 403）。
- 密钥只来自环境变量（ERP 存 BCrypt 散列；MES 侧如 `ERP_MES_CLIENT_SECRET`），任何密钥、令牌不得提交到仓库（两个仓库都是公开的）。

### 不采用 JWKS
ERP **不**校验 MES JWKS，也不接受 MES 签发的令牌；回调只认 ERP 签发的服务令牌。

### ERP → MES（下达）
ERP 向 MES auth-service 申请 `client_credentials`：client `erp-integration`，scope `integration:workorder-dispatch`，aud `manufacturing-api`（沿用 MES #86）。

## 3. 幂等（回调）

| 项 | 规则 |
| --- | --- |
| 键 | 请求头 `Idempotency-Key`，**必须等于** body `requestNo`；请求头缺失、body `requestNo` 缺失或两者不一致 → 400 / code 400 |
| 范围 | `(服务身份 sub, requestNo)` 唯一；与用户维度的 `Idempotency-Key`（24h，`UNIQUE(user_name, idem_key)`）分开存储 |
| 保留期 | ≥ 90 天（实现默认 90 天，可调大不可调小）；同时 ERP 单据上记录 `(sub, requestNo)` 并加唯一约束，保留期过后仍不会重复建单 |
| 同键同内容 | 返回首次结果（200，同一 `erpDocumentNo`） |
| 同键不同内容 | 409 / 40902。摘要按规范化 JSON 计算（字段顺序无关、数值 `1.5` == `1.500000`），并包含接口名 |
| 首次仍在处理 | 409 / 40904，MES 退避重试 |
| 失败不占键 | 400、409 业务冲突、503/50301 等失败不留下幂等记录，修正后可用同一 `requestNo` 重试 |

## 4. 触发时机与业务语义

- **领料（报工倒冲回传）**：MES 报工成功后立即回传本次报工倒冲的物料，一次报工一笔；必填 `operationNo`、`reportId` 追溯到工序 / 报工。ERP 生成生产领料出库单并扣库存。冲销报工的反向单据另议。
- **完工入库**：工单进入 **COMPLETED** 时回传合格品数量；ERP 生成完工入库单。CLOSED 不触发。
- **报废**：在工序上报废时即时回传，一次报废一笔；ERP 生成报废出库单。
- **退料**（MES #72）：必须在工单 **CLOSED** 之前完成（COMPLETED 后、CLOSED 前仍可退料）；退料接口不在 M2 范围，后续另加契约。
- 完工入库时工单未 COMPLETED、或生产订单已 CLOSED / 取消 → 409 / **40903**（现有 `ORDER_STATUS_CHANGED`）。
- `quantity` 超过 6 位小数 → 400，**不做舍入**。
- 回调整笔成功或整笔失败，不支持部分成功。

## 5. 字段映射

| 契约字段 | MES | ERP | 说明 |
| --- | --- | --- | --- |
| `erpProductionOrderNo` | 工单新增 `erp_order_no` | 生产订单号（实现 PR 新增） | 业务主键 |
| `productCode` / `materialCode` | `product` / 物料编码 | mdm 物料编码 | 两边编码一致，不做转换 |
| `quantity` | 改为 6 位小数 | `DECIMAL(18,6)` | 字符串传输，最多 6 位小数；ERP 下达固定 6 位 |
| `uom` | 新增 | 物料计量单位 | 必须与物料主单位一致，否则 400 |
| `warehouseCode` | 透传 | 仓库编码 | 完工入库默认取下达时的仓；报废需显式传（ERP 定稿新增） |
| `deviceId` | 可选 | **不传** | 设备由 MES 下达后分配 |
| `occurredAt` | 发生时间 | 单据业务日期 | ISO-8601 带时区；ERP 单据日期按北京时间换算 |
| `reasonCode` | 报废原因 | 报废原因字典 `SCRAP_REASON` | 字符串，按 ERP 字典校验，不在字典 → 400 / 400；契约不写死枚举。字典在 ERP 主数据维护（实现 PR 新增，初始值 PROCESS_DEFECT / MATERIAL_DEFECT / EQUIPMENT / OTHER），MES 同步该字典 |
| `operationNo` / `reportId` | 报工工序 / 报工记录 ID | 领料单追溯字段 | 仅领料，必填 |
| `warehouseCode`（报废） | 透传 | 仓库编码 | 报废必填 |

## 6. 错误

过渡期 ERP 返回 `R{code,msg,data}`，HTTP 状态 = 错误大类。MES 适配层只按 HTTP 状态 + `code` 映射为自己的 problem type，**不解析 `msg`**。
对照表与重试策略见 [`error-codes.md`](error-codes.md)。

## 7. MES #86 开放问题的结论

| ID | 结论 |
| --- | --- |
| OQ-SRC-1 | ERP 新建本目录，为唯一事实来源 |
| OQ-AUTH-1 | 接受：ERP→MES 用 MES auth-service 签发的 `erp-integration` 令牌 |
| OQ-AUTH-2 | 只用 ERP 签发的 client_credentials；不校验 MES JWKS（§2） |
| OQ-ERR-1 | 过渡期保持 `R`，提供数字码对照表 |
| OQ-IDEM-1 | 按 `(sub, requestNo)` 去重，保留 ≥ 90 天，单据上加唯一约束 |
| OQ-IDEM-2 | 头和体都要，`Idempotency-Key` 必须等于 `requestNo` |
| OQ-CB-1/2/3 | 路径沿用提案，字段见 openapi；整笔成功/失败；单据号由 ERP 生成 |
| OQ-MAP-1 | ERP 不传 `deviceId`，MES 改为可选 |
| OQ-MAP-2 | 6 位小数字符串 |
| OQ-MAP-3 | 物料、仓库编码两边一致；`uom` 须与物料主单位一致 |
| OQ-FLOW-1 | 领料为报工倒冲回传；完工入库在 COMPLETED；报废即时；退料在 CLOSED 前 |

## 8. 边界用例（QA Mock）

| # | 场景 | 期望 |
| --- | --- | --- |
| B1 | 首次合法回调 | 201，`replayed=false` |
| B2 | 同 `requestNo` 同内容重发 | 200，`Idempotent-Replayed: true`，同一 `erpDocumentNo` |
| B3 | 同 `requestNo` 不同参数（或换接口） | 409 / 40902 |
| B4 | 首次仍在处理时重发 | 409 / 40904 |
| B5 | 缺 `Idempotency-Key` 头 | 400 / 400 |
| B6 | 缺 body `requestNo` | 400 / 400 |
| B7 | 头 ≠ `requestNo` | 400 / 400 |
| B8 | `quantity` = `"1.0000001"`（7 位小数） | 400 / 400，不舍入、不落库 |
| B9 | `quantity` = `"1.5"` 与 `"1.500000"` 同 `requestNo` | 视为同一请求，200 重放 |
| B10 | 无令牌 / 过期 / `aud≠erp-api` / MES 签发的令牌 | 401 / 401 |
| B11 | 服务令牌缺对应 scope | 403 / 403 |
| B12 | 用户 JWT 调回调接口 | 403 / 403 |
| B13 | 服务令牌调普通业务接口（如 `POST /api/purchase/orders`） | 403 / 403 |
| B14 | 完工入库时工单未 COMPLETED | 409 / 40903 |
| B15 | 取号繁忙后同 `requestNo` 重试 | 先 503 / 50301，重试 201，只建一张单 |
| B16 | 同 `(sub, requestNo)` 89 天后重发 | 仍按重放返回 200 |
| B17 | 报废 `reasonCode` 不在 `SCRAP_REASON` 字典 | 400 / 400，不落库 |
| B18 | 领料缺 `operationNo` 或 `reportId` | 400 / 400 |
| B19 | 下达：同 `Idempotency-Key` 不同请求体 | 409 / 40902（不是 422） |

所有数量字段（下达、领料行、完工入库、报废）共用 `Decimal6`：最多 6 位小数，超过 → 400，不舍入（B8 对所有回调和下达都适用）。

## 9. 测试约定（Mock）

`openapi.yaml` 每个回调都有完整 examples：首次成功 201、幂等重放 200、400（校验 / 缺键 / 键与 requestNo 不一致）、401（缺令牌 / 过期 / aud 不符）、403（scope 不符）、409（40902 / 40904 / 业务冲突）、503（50301）。
双方据此生成 Mock 与契约测试。lint：`npx @redocly/cli lint docs/integration/openapi.yaml`。
