# ERP ↔ MES 集成（M2）

> **本目录是 ERP↔MES M2 集成的唯一事实来源。** 双方不做端到端联调，按本契约实现，用 Mock 测试。
> 契约改动走 PR，由 PM 审查。当前只有契约，ERP 侧接口实现另开 PR。

| 文件 | 内容 |
| --- | --- |
| [`openapi.yaml`](openapi.yaml) | OpenAPI 3.1：三个 MES→ERP 回调（ERP 托管，权威），以及 ERP→MES 下达（MES 托管，调用方视角） |
| [`error-codes.md`](error-codes.md) | 数字码 ↔ problem type ↔ 含义 ↔ 重试策略 |

来源：MES 提案 [smart-manufacturing-cloud#86](https://github.com/Akarin7784/smart-manufacturing-cloud/pull/86)
（`docs/api/erp-integration-openapi.yaml`、`docs/erp-mes-integration-design.md`，head `25670ca`）。
路径和字段沿用该提案，与下面 PM 定稿冲突处以本目录为准。

## 0. PM 定稿（覆盖 MES 提案）

1. 本目录为唯一事实来源，路径/字段对齐 MES #86 + 以下决定。
2. MES→ERP 回调使用服务账号鉴权（§2）。
3. 三个回调：领料 / 完工入库 / 报废，按 `requestNo` 长期去重，不用 24h 用户维幂等。
4. 下达 `quantity` 为 6 位小数字符串；ERP 不传 `deviceId`（MES 侧改为可选）。
5. 错误体过渡期保持 `R{code,msg,data}`，给出数字码对照表（`error-codes.md`）。
6. 回调请求头 `Idempotency-Key` 必须等于 body `requestNo`；按服务身份去重；保留 ≥ 90 天。
7. 完工入库在工单 **COMPLETED** 时触发（不是 CLOSED）。
8. 领料在下达/领料单之后；报废在工序上即时回传；退料须在完工前。

## 1. 接口一览

| 方向 | 接口 | 托管方 | 触发 | 幂等 |
| --- | --- | --- | --- | --- |
| ERP→MES | `POST /api/integration/erp/work-orders/dispatch` | MES | ERP 生产订单审批通过 | `Idempotency-Key` = `erpProductionOrderNo` |
| MES→ERP | `POST /api/integration/mes/material-issues` | ERP | 下达后按领料单发料 | `requestNo`（`MI-{工单号}-{序号}`） |
| MES→ERP | `POST /api/integration/mes/finished-goods-receipts` | ERP | 工单 COMPLETED | `requestNo`（`FGR-{工单号}-{序号}`） |
| MES→ERP | `POST /api/integration/mes/scraps` | ERP | 工序报废即时 | `requestNo`（`SCR-{工单号}-{工序}-{序号}`） |

回调成功：首次 **201**；重放 **200** + 响应头 `Idempotent-Replayed: true` + `data.replayed=true`，`erpDocumentNo` 与 `acceptedAt` 与首次相同。

## 2. 鉴权

### MES → ERP（回调）—— 推荐：ERP 签发 client_credentials（已采用）
- ERP 新增服务账号机制：`POST /api/auth/oauth2/token`，`grant_type=client_credentials`，`client_secret_basic`。
- MES 客户端 `mes-integration`；令牌为 ERP 签发的 JWT：`aud=erp-api`，`sub=svc:mes-integration`，
  `scope` ⊆ `erp:integration:material-issue erp:integration:finished-goods-receipt erp:integration:scrap`，有效期建议 ≤ 15 分钟。
- 选择理由：鉴权由 ERP 自己负责，服务身份（`sub`）直接作为幂等隔离维度；吊销/轮换不依赖 MES 可用性；ERP 已有 JWT 签发基础。
- 服务令牌不带用户权限，不能调用业务接口；用户令牌调集成接口返回 403。
- 错误：令牌缺失/无效/过期/`aud` 不符 → **401**（code 401）；scope 不符 → **403**（code 403）。
- 密钥只来自环境变量（ERP 存 BCrypt 散列；MES 侧如 `ERP_MES_CLIENT_SECRET`），任何密钥、令牌不得提交到仓库（两个仓库都是公开的）。

### 备选：ERP 校验 MES JWKS（未采用）
ERP 拉取 MES auth-service 的 JWKS，校验 MES 签发的服务令牌（约定 `aud=erp-api`、`iss`、scope）。
省去 ERP 侧签发，但 ERP 的认证依赖 MES 的密钥管理和可用性，且需要两边对 `iss/aud/scope` 再做约定。
若后续统一到 MES auth-service 再评估切换；契约中的 scope 名与 401/403 语义保持不变。

### ERP → MES（下达）
ERP 向 MES auth-service 申请 `client_credentials`：client `erp-integration`，scope `integration:workorder-dispatch`，aud `manufacturing-api`（沿用 MES #86）。

## 3. 幂等（回调）

| 项 | 规则 |
| --- | --- |
| 键 | 请求头 `Idempotency-Key`，**必须等于** body `requestNo`；缺失 → 400；不等 → 400 / code 40002 |
| 范围 | `(服务身份 sub, requestNo)` 唯一；与用户维度的 `Idempotency-Key`（24h，`UNIQUE(user_name, idem_key)`）分开存储 |
| 保留期 | ≥ 90 天（实现默认 90 天，可调大不可调小）；同时 ERP 单据上记录 `(sub, requestNo)` 并加唯一约束，保留期过后仍不会重复建单 |
| 同键同内容 | 返回首次结果（200，同一 `erpDocumentNo`） |
| 同键不同内容 | 409 / 40902。摘要按规范化 JSON 计算（字段顺序无关、数值 `1.5` == `1.500000`），并包含接口名 |
| 首次仍在处理 | 409 / 40904，MES 退避重试 |
| 失败不占键 | 400、409 业务冲突、503/50301 等失败不留下幂等记录，修正后可用同一 `requestNo` 重试 |

## 4. 触发时机与业务语义

- **领料**：MES 工单下达（派工）后，按领料单实际发料时回传，ERP 生成生产领料出库单并扣库存。一张领料单可多次回传（序号递增）。
- **完工入库**：工单进入 **COMPLETED** 时回传合格品数量；ERP 生成完工入库单。CLOSED 不触发。
- **报废**：在工序上报废时即时回传，一次报废一笔；ERP 生成报废出库单。
- **退料**（MES #72）：必须在工单 COMPLETED 之前完成；退料接口不在 M2 范围，后续另加契约。
- 生产订单已 CLOSED / 取消后的回传 → 409 / 40905。
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
| `reasonCode` | 报废原因 | 枚举 | `PROCESS_DEFECT / MATERIAL_DEFECT / EQUIPMENT / OTHER` |

## 6. 错误

过渡期 ERP 返回 `R{code,msg,data}`，HTTP 状态 = 错误大类。MES 适配层只按 HTTP 状态 + `code` 映射为自己的 problem type，**不解析 `msg`**。
对照表与重试策略见 [`error-codes.md`](error-codes.md)。

## 7. MES #86 开放问题的结论

| ID | 结论 |
| --- | --- |
| OQ-SRC-1 | ERP 新建本目录，为唯一事实来源 |
| OQ-AUTH-1 | 接受：ERP→MES 用 MES auth-service 签发的 `erp-integration` 令牌 |
| OQ-AUTH-2 | ERP 签发 client_credentials（推荐、已采用）；JWKS 为备选（§2） |
| OQ-ERR-1 | 过渡期保持 `R`，提供数字码对照表 |
| OQ-IDEM-1 | 按 `(sub, requestNo)` 去重，保留 ≥ 90 天，单据上加唯一约束 |
| OQ-IDEM-2 | 头和体都要，`Idempotency-Key` 必须等于 `requestNo` |
| OQ-CB-1/2/3 | 路径沿用提案，字段见 openapi；整笔成功/失败；单据号由 ERP 生成 |
| OQ-MAP-1 | ERP 不传 `deviceId`，MES 改为可选 |
| OQ-MAP-2 | 6 位小数字符串 |
| OQ-MAP-3 | 物料、仓库编码两边一致；`uom` 须与物料主单位一致 |
| OQ-FLOW-1 | 领料在下达/领料单后；完工入库在 COMPLETED；报废即时；退料在完工前 |

## 8. 测试约定（Mock）

`openapi.yaml` 每个回调都有完整 examples：首次成功 201、幂等重放 200、400（校验 / 缺键 / 键与 requestNo 不一致）、401（缺令牌 / 过期 / aud 不符）、403（scope 不符）、409（40902 / 40904 / 业务冲突）、503（50301）。
双方据此生成 Mock 与契约测试。lint：`npx @redocly/cli lint docs/integration/openapi.yaml`。
