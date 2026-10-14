# ERP —— 传统多模块 ERP 系统（Java）

面向中小制造/贸易企业的进销存 + 轻财务 ERP：主数据、采购、销售、库存、应收应付，配套 RBAC 权限与审批流。
定位是「朴实、业务闭环完整」：单据流转、出入库与库存流水、审批驱动状态机。

## 技术栈
Java 17 · Spring Boot 3.3 · Spring Security + JWT · MyBatis-Plus 3.5 · MySQL 8（本地可用 H2）· Redis（已预留）· Maven 多模块

## 模块
| 模块 | 职责 |
|---|---|
| erp-common | 统一响应 `R`、业务异常与全局异常处理、`BaseEntity`（审计字段/逻辑删除）、MyBatis-Plus 分页与乐观锁、单号生成、审批事件 |
| erp-auth | 登录签发 JWT、RBAC，用户 / 角色 / 权限点管理，token 版本号（停用、改密、调权后旧 token 失效） |
| erp-mdm | 主数据：物料、客户、供应商、仓库；编码不可改、不可复用，被引用时不能删除（引用检查 SPI） |
| erp-workflow | 多级审批：按单据类型配置流程，节点按角色指定审批人，支持会签 / 或签和金额条件；审批记录、待我审批；通过 Spring 事件回调业务模块 |
| erp-inventory | 即时库存 + 库存流水；乐观锁重试防并发超扣；手工出入库 |
| erp-purchase | 采购订单：草稿 → 提交审批 → 已审批 → 收货入库 |
| erp-sales | 销售订单：草稿 → 提交审批 → 已审批 → 发货出库 |
| erp-finance | 应收/应付自动生成、收付款登记、核销（部分核销/幂等/乐观锁）、往来对账 |
| erp-boot | 启动模块，聚合所有模块，配置与 SQL 初始化脚本 |

依赖方向：`common ← auth/mdm/workflow/finance ← inventory ← purchase/sales ← boot`。审批完成通过 `ApprovalFinishedEvent` 通知业务模块，workflow 不依赖任何业务模块。

## 本地启动
需要 JDK 17、Maven 3.9。

```bash
# 方式一：H2 内存库（默认，零依赖）
mvn -DskipTests package
java -jar erp-boot/target/erp-boot-0.1.0-SNAPSHOT.jar
# H2 控制台：http://localhost:8080/h2-console  (jdbc:h2:mem:erp / sa)

# 方式二：MySQL + Redis
docker compose up -d
SPRING_PROFILES_ACTIVE=mysql java -jar erp-boot/target/erp-boot-0.1.0-SNAPSHOT.jar
```
表结构与初始数据由 `erp-boot/src/main/resources/db/schema.sql`、`data.sql` 自动初始化。
H2 profile 内置演示主数据（`db/demo-data.sql`，MySQL 不加载）：物料 5 条（DM-M001~005）、客户 / 供应商 / 仓库各 3 条（DM-C/S/W），id 从 1 开始，可以直接用于下面的演示。
H2 profile 默认账号 `admin / admin123`（可用 `ERP_ADMIN_PASSWORD` 覆盖）。MySQL 等其他 profile 没有默认密码：首次启动必须通过环境变量 `ERP_ADMIN_PASSWORD` 或配置 `erp.init.admin-password` 提供 admin 初始密码，否则启动失败。H2 控制台只在 H2 profile 开启。JWT 密钥用 `ERP_JWT_SECRET` 覆盖。

## 演示：采购收货全流程
```bash
H='Content-Type: application/json'
T=$(curl -s -XPOST localhost:8080/api/auth/login -H "$H" -d '{"username":"admin","password":"admin123"}' | jq -r .data.token)
A="Authorization: Bearer $T"
curl -XPOST localhost:8080/api/mdm/materials  -H "$H" -H "$A" -d '{"code":"M001","name":"螺丝","unit":"个"}'
curl -XPOST localhost:8080/api/mdm/suppliers  -H "$H" -H "$A" -d '{"code":"S001","name":"供应商A"}'
curl -XPOST localhost:8080/api/mdm/warehouses -H "$H" -H "$A" -d '{"code":"W01","name":"主仓"}'
curl -XPOST localhost:8080/api/purchase/orders -H "$H" -H "$A" \
  -d '{"supplierId":1,"warehouseId":1,"items":[{"materialId":1,"qty":100,"price":0.5}]}'
curl -XPOST localhost:8080/api/purchase/orders/1/submit -H "$A"                      # 返回 approvalId
curl -XPOST localhost:8080/api/workflow/approvals/1/decide -H "$H" -H "$A" -d '{"approved":true}'
curl -XPOST localhost:8080/api/purchase/orders/1/receive -H "$A"                     # 入库
curl localhost:8080/api/inventory/stocks -H "$A"
curl localhost:8080/api/inventory/txns   -H "$A"
```

## 系统管理（P2）
- 读接口需要 `sys:read`，写接口需要 `sys:write`（admin 已预置）。
- 密码用 BCrypt 存储，至少 6 位；接口不会返回密码。
- JWT 里带 `tv`（token 版本号），每次请求都和数据库比对。用户被停用、删除、重置密码、调整角色，或者所属角色的权限点有变化时，版本号 +1，旧 token 立即返回 401。
- 不能删除或停用当前登录用户；不能删除、停用最后一个启用的管理员，也不能去掉他的 ADMIN 角色；内置的 ADMIN 角色不能删除。
- 用户名、角色编码、权限点编码都唯一，并且不能复用已删除记录的编码（重复时返回 40001）；角色和权限点的编码创建后不可修改。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/sys/users?page&size&keyword | 用户分页，返回 `{id,username,realName,enabled,roleCodes}` |
| GET | /api/sys/users/{id} | 用户详情 |
| POST | /api/sys/users | `{username,password,realName?,roleIds?}` |
| PUT | /api/sys/users/{id} | `{realName}` |
| PUT | /api/sys/users/{id}/password | `{password}` |
| PUT | /api/sys/users/{id}/status | `{enabled}` |
| PUT | /api/sys/users/{id}/roles | `{ids:[roleId]}`，覆盖式分配 |
| DELETE | /api/sys/users/{id} | 逻辑删除 |
| GET/POST | /api/sys/roles | 列表 / 新建 `{code,name}` |
| GET/PUT/DELETE | /api/sys/roles/{id} | 详情（含 permissionIds）/ 改名 / 删除 |
| PUT | /api/sys/roles/{id}/permissions | `{ids:[permissionId]}`，覆盖式分配 |
| GET/POST | /api/sys/permissions | 列表 / 新建 `{code,name}` |
| PUT/DELETE | /api/sys/permissions/{id} | 改名 / 删除 |

## 多级审批（P2）
- 按单据类型（`PURCHASE_ORDER`、`SALES_ORDER`）配置流程：节点按顺序执行，每个节点指定审批角色 `roleCode`，审批方式是 `ANY`（或签）或 `ALL`（会签，该角色下所有启用用户都要同意）。可选 `minAmount`：单据金额**严格大于**它时才进入这个节点（等于不进入）。
- 提交时按金额选出要走的节点，并把节点快照保存下来。存在审批中的实例时，不能修改或删除该单据类型的流程。
- 只有当前节点的审批人能处理，否则返回 403；同一个人在同一节点只能处理一次。任一节点驳回，单据回到 **DRAFT**，可以修改后重新提交。
- 会签名单在提交时冻结（`approver_snapshot`）：之后再加入该角色的人不能审批，离开该角色的人也不再算在名单里不影响已冻结的人数要求；申请人本人不进名单，某节点除申请人外没有可用审批人时提交返回 400。
- 申请人不能审批自己的单据（admin 也一样），返回 403；自己的单据不出现在待办里。演示环境（H2）预置审批人 `approver / approver123`。
- 同一人同一节点重复审批由唯一索引 `uk_record_approver` 兜底，并发重复提交只有一次生效，其余返回 409/40900 或 400。
- 配置了审批节点时，ADMIN 不能代审：只有节点角色内（且在会签名单里）的用户能处理，ADMIN 不在其中也返回 403。
- 没有配置流程或流程已停用时，兼容原来的单级审批：持有 `workflow:approve` 的用户（或 ADMIN）都能审批，同样不能审批自己的单据。
- 流程配置需要 `workflow:admin`。

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | /api/workflow/definitions | 全部流程 |
| GET | /api/workflow/definitions/{bizType} | `{process, nodes}` |
| PUT | /api/workflow/definitions/{bizType} | 新建或整体替换：`{name, enabled?, nodes:[{name, roleCode, approveMode: ALL|ANY, minAmount?}]}` |
| DELETE | /api/workflow/definitions/{bizType} | 删除 |
| GET | /api/workflow/approvals/todo | 待我审批 |
| GET | /api/workflow/approvals?bizType&bizId | 审批历史 |
| GET | /api/workflow/approvals/{id} | `{instance, plan, currentNode, records}` |
| POST | /api/workflow/approvals/{id}/decide | `{approved, comment}` |
| GET | /api/workflow/approvals/pending | 全部审批中的实例（需要 `workflow:admin`） |

```bash
# 采购流程：部门经理或签 → 财务会签 → 金额 > 1000 时总经理
curl -XPUT localhost:8080/api/workflow/definitions/PURCHASE_ORDER -H "$H" -H "$A" -d '{"name":"采购审批","nodes":[
  {"name":"部门经理","roleCode":"MGR","approveMode":"ANY"},
  {"name":"财务会签","roleCode":"FIN","approveMode":"ALL"},
  {"name":"总经理","roleCode":"GM","approveMode":"ANY","minAmount":1000}]}'
curl localhost:8080/api/workflow/approvals/todo -H "Authorization: Bearer $MGR_TOKEN"
```

## 主数据规则（P2）
- 编码创建后不可修改：更新时传了不同的编码，返回 400。
- 删除是逻辑删除，编码不能复用，重复时返回 400 / 40001，msg 是"编码已存在（含已删除）"。
- 被采购单、销售单、库存或库存流水、应收应付单引用的主数据不能删除，返回 409 / **40901**，只能停用（PUT 时传 `status=0`）。引用检查通过 SPI `MasterDataReferenceChecker` 实现：由各业务模块注册，mdm 不依赖业务模块。
- 请求校验：编码 `@NotBlank @Size(max=64)`，名称 `@NotBlank @Size(max=128)`，`status` 只能是 0 或 1。

## 主要接口
- `POST /api/auth/login`、`GET /api/auth/me`
- `/api/mdm/{materials|customers|suppliers|warehouses}`：GET 分页（`keyword`）、GET/{id}、POST、PUT/{id}、DELETE/{id}
- `/api/purchase/orders`：GET、POST、GET/{id}、POST/{id}/submit、POST/{id}/receive
- `/api/sales/orders`：GET、POST、GET/{id}、POST/{id}/submit、POST/{id}/ship
- `/api/inventory/stocks`、`/api/inventory/txns`、`POST /api/inventory/in`、`POST /api/inventory/out`
- `GET /api/finance/bills`（`billType`/`partnerId`/`status`/`sourceBizNo`）、`GET /api/finance/bills/{id}`
- `POST /api/finance/payments` 收付款登记，`GET /api/finance/payments`
- `POST /api/finance/settlements` 核销，`GET /api/finance/settlements?billId=`
- `GET /api/finance/reconciliation?billType=AR|AP&partnerId=` 对账汇总

## 约定
- 健康检查：`GET /actuator/health`，不需要登录，只暴露 health 端点。
- 响应体统一为 `{code,msg,data}`；出错时 HTTP 状态表示大类，`code` 是细分码，见下方错误码表。
- 金额参数（收付款金额、核销金额、流程金额条件）要求整数部分最多 16 位、最多 2 位小数；数量和单价要求整数部分最多 14 位、最多 4 位小数。位数在去掉末尾的 0 之后计算：`1.230`、`1.2300`、`1.00` 都算 2 位以内，可以通过；`1.231` 返回 400。超出直接拒绝，不做四舍五入。接口层（`@MoneyScale`）和服务层（`Money.requireAmount`）用的是同一套规则（`Money.scaleError`）。收付款、核销金额还要求 ≥0.01。提示均为中文并带字段名，例如"核销金额最多 2 位小数"、"单价整数部分最多 14 位"。数量×单价这类计算结果才按 HALF_UP 保留 2 位。
- 资源不存在：**路径**里的资源不存在返回 404（如 `GET /api/mdm/suppliers/999`、`GET /api/finance/bills/999`）；**请求体**里引用的对象不存在返回 400（如收付款的 `partnerId`、核销的 `billId`/`paymentId`、订单的 `supplierId`/`customerId`/`warehouseId`/`materialId`、出入库的物料和仓库），提示形如"供应商不存在: 999"。
- 权限先于校验：写接口先判权限再绑定参数、做 `@Valid` 校验。没有权限的用户即使请求体缺字段，也返回 403 而不是 400。
- 更新接口的编码字段：四类主数据、角色、权限点的 `PUT` 中 `code` 可选。不传表示不改，传相同的值可以通过，传不同的值返回 400（编码不可修改）。创建时 `code` 仍然必填。用户更新接口本来就不含用户名，不受影响。

## 系统管理的保护规则
- `ROLE_ADMIN` 对所有 `hasAuthority` 校验放行：即使 ADMIN 角色的权限被清空，管理员也不会被锁在系统外。
- 内置权限点（`data.sql` 预置的那些）不能删除，返回 400；ADMIN 角色不能删除；ADMIN 角色的权限不能修改或清空，返回 400（"ADMIN 角色权限不可修改"）。
- 只有 ADMIN 能授予或收回 ADMIN 角色（包括创建用户时带上 ADMIN），其他人返回 403。

## 已有 MySQL 库的升级脚本
`schema.sql` 只用 `CREATE TABLE IF NOT EXISTS`，对已有的 MySQL 库不会补字段。从 main（P1）升级需要手工执行：
```sql
ALTER TABLE sys_user ADD COLUMN token_version INT NOT NULL DEFAULT 0;
ALTER TABLE wf_approval_instance
  ADD COLUMN amount DECIMAL(18,2),
  ADD COLUMN node_plan VARCHAR(512),
  ADD COLUMN approver_snapshot VARCHAR(4000),
  ADD COLUMN current_index INT NOT NULL DEFAULT 0,
  ADD COLUMN version INT NOT NULL DEFAULT 0;
CREATE TABLE IF NOT EXISTS wf_process_def (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, biz_type VARCHAR(32) NOT NULL UNIQUE, name VARCHAR(64) NOT NULL,
  enabled TINYINT NOT NULL DEFAULT 1, created_at DATETIME, updated_at DATETIME, created_by VARCHAR(64), deleted TINYINT DEFAULT 0);
CREATE TABLE IF NOT EXISTS wf_node_def (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, process_id BIGINT NOT NULL, seq INT NOT NULL, name VARCHAR(64) NOT NULL,
  role_code VARCHAR(64) NOT NULL, approve_mode VARCHAR(8) NOT NULL, min_amount DECIMAL(18,2),
  created_at DATETIME, updated_at DATETIME, created_by VARCHAR(64), deleted TINYINT DEFAULT 0,
  INDEX idx_node_process (process_id, seq));
CREATE TABLE IF NOT EXISTS wf_approval_record (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, instance_id BIGINT NOT NULL, node_id BIGINT, node_name VARCHAR(64),
  node_index INT NOT NULL DEFAULT 0, approver VARCHAR(64) NOT NULL, action VARCHAR(16) NOT NULL, comment VARCHAR(255),
  created_at DATETIME, updated_at DATETIME, created_by VARCHAR(64), deleted TINYINT DEFAULT 0,
  UNIQUE KEY uk_record_approver (instance_id, node_index, approver));
```
主数据、单据等表的 `created_at` / `updated_at` 已加 `DEFAULT CURRENT_TIMESTAMP`（直接 SQL 插入的种子数据也有时间）；已有库可按需执行，例如：
```sql
ALTER TABLE mdm_material MODIFY created_at DATETIME DEFAULT CURRENT_TIMESTAMP, MODIFY updated_at DATETIME DEFAULT CURRENT_TIMESTAMP;
-- mdm_customer / mdm_supplier / mdm_warehouse 等其他表同理
```
从 #18 起单号用数据库序列表（启动时 `CREATE TABLE IF NOT EXISTS` 会自动建，也可手工执行）：
```sql
CREATE TABLE IF NOT EXISTS sys_doc_seq (seq_key VARCHAR(64) NOT NULL PRIMARY KEY, val BIGINT NOT NULL);
```
新的权限点（8 `sys:read`、9 `sys:write`、10 `workflow:admin`）由 `data.sql` 幂等插入，启动时自动补齐。

## 乐观锁（version）
- 四类主数据、用户、角色、权限点、**采购单、销售单、流程定义**都有 `version` 字段（MyBatis-Plus `@Version`），列表和详情接口都会返回。
- 更新接口（`PUT /api/mdm/*/{id}`、`PUT /api/sys/users/{id}`、`PUT /api/sys/roles/{id}`、`PUT /api/sys/permissions/{id}`、`PUT /api/purchase/orders/{id}`、`PUT /api/sales/orders/{id}`、`PUT /api/workflow/definitions/{bizType}`）必须在请求体里带上查询到的 `version`：
  - 不传返回 400（"version 不能为空…"）；
  - 和库里的不一致（别人已经改过）返回 409/40900（"数据已被修改，请刷新后重试"），前端应重新拉取详情再编辑；
  - 成功后返回的 `version` 加 1。
- 已有 MySQL 库需要执行：
```sql
ALTER TABLE mdm_material  ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE mdm_customer  ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE mdm_supplier  ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE mdm_warehouse ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE sys_user       ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE sys_role       ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE sys_permission ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE pur_order       ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE sal_order       ADD COLUMN version INT NOT NULL DEFAULT 0;
ALTER TABLE wf_process_def  ADD COLUMN version INT NOT NULL DEFAULT 0;
```

## 金额与数量（BigDecimal）
- 响应里所有 BigDecimal 字段都序列化为**字符串**（`toPlainString`，不会出现科学计数法），例如 `"amount":"1234567890123456.12"`，避免 JS number 丢精度。
- 请求体里字符串和数字都接收，**推荐传字符串**（`"amount":"12.30"`）。非法值（如 `"abc"`、`"1.2.3"`）返回 400，提示带字段名，例如 "amount 必须是数字"、"items[0].price 必须是数字"。
- 全部 BigDecimal 字段（主数据里没有 BigDecimal 字段）：

| 模块 | 字段 | 精度 |
|---|---|---|
| 采购 / 销售订单 | `totalAmount` | 金额，2 位 |
| 采购 / 销售订单明细 | `qty`、`price` | 数量、单价，最多 4 位小数 |
| 采购 / 销售订单明细 | `amount`（行金额 = 数量×单价，HALF_UP 保留 2 位） | 金额，2 位 |
| 库存 `inv_stock` | `qty` | 数量，4 位 |
| 库存流水 `inv_stock_txn` | `qty`、`balanceAfter` | 数量，4 位 |
| 应收应付单 | `amount`、`settledAmount` | 金额，2 位 |
| 收付款 | `amount`、`appliedAmount` | 金额，2 位 |
| 核销 | `amount` | 金额，2 位 |
| 对账汇总 | `totalAmount`、`settledAmount`、`balance` | 金额，2 位 |
| 流程节点 | `minAmount` | 金额，2 位 |
| 审批实例 | `amount` | 金额，2 位 |

- 单价按原值保存（最多 4 位小数），不会先舍入成 2 位；只有行金额和合计按 2 位保留。
- 行金额、订单合计超出金额列上限（整数部分 16 位）返回 400，例如"第 1 行金额整数部分最多 16 位，超出金额上限"；入库后库存数量超过 14 位整数也返回 400，不再是 500。
- 校验提示：`@NotNull`、`@NotBlank` 等默认提示是中文，前面带字段名，例如 "paymentType 不能为空"、"status 不能大于 1"；业务上自定义的提示（金额、数量等）原样返回。

## 错误码
响应体 `code` 是细分码，HTTP 状态是大类（定义在 `erp-common` 的 `ErrorCode` 枚举里）。通用错误的 `code` 就等于 HTTP 状态码；业务细分码按 HTTP 状态 × 100 + 序号编排。

| HTTP 状态 | code | 含义 | 触发场景 |
|---|---|---|---|
| 400 | 400 | 参数或业务校验失败 | 所有接口的 `@Valid` 校验失败（例如 `POST /api/finance/settlements` 金额 <0.01 或超过 2 位小数），请求体格式错误、参数类型错误；业务规则不满足：`POST /api/purchase/orders/{id}/receive`、`/api/sales/orders/{id}/ship` 时订单状态不是 APPROVED，`POST /api/inventory/out` 或发货时库存不足，`POST /api/finance/settlements` 超额核销、核销已核销的单据、收付款类型或往来单位不匹配 |
| 400 | 40001 | 编码已存在（msg 以"编码已存在"开头） | `POST /api/mdm/{materials,customers,suppliers,warehouses}`、`POST /api/sys/{users,roles,permissions}` 时编码 / 用户名重复，**包括已逻辑删除的**；并发同编码创建撞唯一索引也返回这个码 |
| 401 | 401 | 未登录或登录已失效 | 不带 token 或 token 无效时访问除 `/api/auth/login`、`/actuator/health` 以外的接口；`POST /api/auth/login` 用户名或密码错误 |
| 403 | 403 | 无权限 | 缺少对应权限点，例如没有 `finance:read` 时调用 `GET /api/finance/*`，没有 `finance:write` 时调用 `POST /api/finance/payments`、`/settlements`；登录时账号已停用 |
| 404 | 404 | 资源不存在 | 路径不存在；路径里的资源查不到，例如 `GET /api/finance/bills/{id}`、`GET /api/mdm/*/{id}`、`PUT /api/purchase/orders/{id}`。**先判路径资源**：路径资源不存在一律 404，优先于请求体里的 version、引用等检查 |
| 405 | 405 | 请求方法不支持 | 例如 `PUT /api/finance/reconciliation` |
| 409 | 40900 | 并发冲突（乐观锁或状态已变化），提示"数据已被修改，请刷新后重试" | `POST /api/finance/settlements` 时不同请求号争抢同一单据或收付款（同一请求号的并发重复提交按幂等处理，不返回 40900）；`POST /api/purchase/orders/{id}/receive`、`/api/sales/orders/{id}/ship` 被并发重复调用；死锁或锁等待超时（含 H2 锁超时后回滚失败）在最外层整事务自动重试 3 次后仍失败（每次重试记 WARN 日志），返回 HTTP 409，不会是 500；首次收货/入库（库存行尚不存在）并发不再返回 40900（#13）；同一审批人对同一节点并发重复审批；其他未被业务处理的数据库唯一键冲突 |
| 409 | 40901 | 主数据已被引用，不能删除 | `DELETE /api/mdm/*/{id}`：被订单、库存、应收应付单、收付款引用 |
| 409 | 40902 | 幂等请求号复用但参数不同 | `POST /api/finance/settlements` 时同一个 `requestNo` 对应的 billId、paymentId 或 amount 和第一次不一致 |；`Idempotency-Key` 复用但请求参数不同，或同一 key 用在别的创建接口（#22）
| 409 | 40903 | 单据状态已变更，提示"单据状态已变更，请刷新后重试" | `PUT /api/purchase/orders/{id}`、`PUT /api/sales/orders/{id}`：订单已不是草稿（读出时已不是草稿，或并发提交/审批导致定向更新影响 0 行后重读发现不是草稿）。参数校验和引用检查（供应商/客户、仓库、物料是否存在）先于状态判断：非法输入一律 400 |
| 409 | 40904 | 请求处理中，请稍后重试 | 带 `Idempotency-Key` 的创建请求，同一用户同一 key 的上一次请求还在处理且租期未过（并发重复提交）；租期过后同 key 会查已提交单据并重放，或接管后创建 |
| 500 | 500 | 系统异常 | 未预期的异常 |
| 503 | 50301 | 取号繁忙，请稍后重试 | 创建单据（采购单、销售单、手工出入库、收付款）及收货/发货时取单据号超时：取号连接池 1 秒内拿不到连接，或序号行锁死锁/等待超时且就地重试后仍失败（例如被外部会话锁住；最坏约 4 秒，常见约 2 秒）。此时还没开业务事务、没写任何数据，可直接重试；带 `Idempotency-Key` 时用同一 key 重试 |

## 财务闭环
- 权限：查询类接口需要 `finance:read`，收付款登记和核销需要 `finance:write`（admin 已预置这两个权限）。
- 收货和发货用带状态条件的更新（`APPROVED → RECEIVED/SHIPPED`）。并发重复提交时只有一个请求成功，其余返回 409；顺序重复提交返回 400。
- 采购收货 / 销售发货完成后发布 `StockBizCompletedEvent`，财务同步监听（同一事务）生成 **应付 AP / 应收 AR**；金额 = Σ 数量×单价（BigDecimal，2 位，HALF_UP），按来源单号唯一，重复事件不重复生成。
- 收付款：`PAYMENT` 付款（供应商）只能核销 AP，`RECEIPT` 收款（客户）只能核销 AR，往来单位必须一致。
- 核销状态机：`OPEN 未核销 → PARTIAL 部分核销 → SETTLED 已核销`；核销金额不得超过单据余额，也不得超过收付款未核销余额。
- 幂等：`requestNo` 唯一。同号同参数重放返回原结果；同号不同参数返回 409/40902。并发同号提交时，失败方（撞唯一索引或乐观锁冲突）回滚后按请求号重新查一次：已有成功记录且参数相同返回原结果，参数不同返回 40902；没有记录（是别的请求号抢先）才返回 40900。
- 并发：应收应付单、收付款都有 `@Version` 乐观锁；冲突时整笔回滚返回 409，由调用方重试。

```bash
# 接上面的采购演示（订单已收货），查应付并付款核销
BILL=$(curl -s "localhost:8080/api/finance/bills?billType=AP" -H "$A" | jq .data.records[0].id)
PAY=$(curl -s -XPOST localhost:8080/api/finance/payments -H "$H" -H "$A" \
  -d '{"paymentType":"PAYMENT","partnerId":1,"amount":50}' | jq .data.id)
curl -XPOST localhost:8080/api/finance/settlements -H "$H" -H "$A" \
  -d "{\"requestNo\":\"req-001\",\"paymentId\":$PAY,\"billId\":$BILL,\"amount\":20}"   # PARTIAL
curl -XPOST localhost:8080/api/finance/settlements -H "$H" -H "$A" \
  -d "{\"requestNo\":\"req-002\",\"paymentId\":$PAY,\"billId\":$BILL,\"amount\":30}"   # SETTLED
curl "localhost:8080/api/finance/reconciliation?billType=AP" -H "$A"

# 销售：先入库，再下单→审批→发货→应收→收款核销
curl -XPOST localhost:8080/api/mdm/customers -H "$H" -H "$A" -d '{"code":"C001","name":"客户A"}'
curl -XPOST localhost:8080/api/sales/orders -H "$H" -H "$A" \
  -d '{"customerId":1,"warehouseId":1,"items":[{"materialId":1,"qty":40,"price":1.2}]}'
curl -XPOST localhost:8080/api/sales/orders/1/submit -H "$A"                  # 返回 approvalId，假设为 2
curl -XPOST localhost:8080/api/workflow/approvals/2/decide -H "$H" -H "$A" -d '{"approved":true}'
curl -XPOST localhost:8080/api/sales/orders/1/ship -H "$A"                    # 出库 + 生成 AR 48.00
curl -XPOST localhost:8080/api/finance/payments -H "$H" -H "$A" -d '{"paymentType":"RECEIPT","partnerId":1,"amount":48}'
curl -XPOST localhost:8080/api/finance/settlements -H "$H" -H "$A" \
  -d '{"requestNo":"req-003","paymentId":2,"billId":2,"amount":48}'
curl "localhost:8080/api/finance/reconciliation?billType=AR" -H "$A"
```

## 测试
```bash
mvn verify   # 单元测试 + H2 集成测试 + JaCoCo 聚合报告 + 覆盖率门禁（行、分支都不低于 80%，不达标构建失败）
# 报告：erp-boot/target/site/jacoco-aggregate/index.html
```

## 当前进度
- [x] 多模块骨架、统一响应/异常、审计字段、逻辑删除、分页
- [x] JWT 登录 + RBAC 权限点鉴权
- [x] 主数据 CRUD
- [x] 采购/销售订单 + 单级审批 + 审批事件驱动状态
- [x] 库存出入库、流水、乐观锁防超扣
- [x] 财务闭环：自动应收应付、收付款、核销、对账；JUnit5 + H2 集成测试 + JaCoCo

## 后续规划
1. 财务：红冲/反核销、账龄分析、预收预付、发票
2. 操作日志（关键写操作审计）
3. 采购：采购申请 → 订单、分批到货、退货；销售：发货单、部分发货、退货
4. 库存：调拨、盘点、库存冻结/预占、批次与库位、月结
5. 审批流：流程定义（多节点、会签/或签、条件分支）、转办与审批记录
7. 用户/角色/菜单管理接口、数据权限（按组织/仓库）、操作日志
6. Redis：单号号段、权限缓存、登录限流；接口文档（springdoc）、Flyway 迁移
7. 单元/集成测试（Testcontainers）、CI、Docker 镜像；与 MES 项目对接（生产领料/完工入库）

## 库存加锁顺序与流水

采购收货、销售发货时，同一单据里同一 (物料, 仓库) 的多行会先合并数量，再按 (materialId, warehouseId) 升序逐个加锁更新库存，所有事务以相同顺序取锁，避免正反序单据并发死锁。库存流水按合并后的 (物料, 仓库) 各记一条，数量为该单据的合计，与库存行的一次变动一一对应（同一物料在单据里写了两行，只生成一条流水）。

## 单号与库存流水前缀（#18）

- **单号格式**：`前缀 + yyyyMMdd + 8 位序号`（如 `PO2026101400000001`），序号来自表 `sys_doc_seq`（按 `前缀+日期` 一行，`INSERT ... ON DUPLICATE KEY UPDATE val = val + 1`）。多实例共享同一库即不会重号。旧格式（前缀 + 14 位时间 + 4 位进程内序号 + 3 位随机数）的历史单号保持不变。
- 取号走独立的小连接池（`doc-no`，最多 4 个连接）里的短事务，立即提交：序列行锁不会持到业务事务结束（否则所有收货会串行，并可能与库存行锁形成死锁），也不占业务连接池。代价是业务回滚后**号可能跳号**，单号只保证唯一、递增，不保证连续。
- **库存流水前缀**：入库 `SMI`、出库 `SMO`，不再与销售订单的 `SO` 冲突。历史流水（`SI…`/`SO…`）不迁移（`txn_no` 无唯一约束，也不被其他表引用）；按前缀筛选流水时需同时考虑旧前缀。
- **软删除的库存行**：`inv_stock` 的唯一键 (material_id, warehouse_id) 包含已软删除的行。出库仍视为无库存（400 库存不足），行保持删除状态；入库时复活该行：`deleted=0`、数量从 0 起算（旧数量作废）、`version+1`，再加上本次入库量。

## 事务重试日志

- 重试 / 重试耗尽的 WARN 写出真实的锁异常（例如 `com.mysql.cj.jdbc.exceptions.MySQLTransactionRollbackException` 或 H2 的 `JdbcSQLTimeoutException`）、`kind=deadlock|lock-timeout` 以及 `sqlState` / `errorCode`，而不是外层的 `TransactionSystemException` / `UncategorizedSQLException`。
- 回滚失败且没有原始业务异常时返回 500，只记一条简短 ERROR（op、异常类、SQLState、错误码），不含 SQL，也不打堆栈。

## 取号与 Idempotency-Key（#22）

**创建请求的顺序**：参数校验（`@Valid`）→ 幂等占位（带 `Idempotency-Key` 时）→ 业务校验（引用的供应商/客户/仓库/物料、金额）→ 从独立取号池取号 → 开业务事务写库。
收货 / 发货也一样：先在事务外按合并后的物料数取好库存流水号和应付/应收单号，再进事务（整事务重试时复用这批号）。
等号期间请求不占主连接池的连接；取号池（`doc-no`，最多 4 个连接）拿连接最多等 `erp.doc-no.connection-timeout-ms`（默认 1000），
序号行锁每次最多等 `erp.doc-no.lock-wait-seconds`（默认 1，MySQL 设会话级 `innodb_lock_wait_timeout`，H2 设 `LOCK_TIMEOUT`）。
取号时就地重试（#22-2，不回滚业务事务——取号本就在业务事务之前），每次换一个取号连接、退避 10~50ms：
死锁（1213 / H2 40001）最多共 3 次；锁等待超时（1205 / H2 50200）最多共 2 次；拿不到取号连接不重试。仍失败返回 **503 / 50301**。
最坏延迟：2 ×（拿连接 ≤1s + 锁等待 1s）+ 退避 ≈ 4.05s；常见的“行被外部长时间锁住”约 2.05s（与原先单次等 2s 相当）。
单号日期按 `Asia/Shanghai`，与 JVM / 容器时区无关；序号超过 8 位时仍唯一（变成 9 位），记一条 WARN。

**`Idempotency-Key` 请求头**：`POST /api/purchase/orders`、`/api/sales/orders`、`/api/inventory/in`、`/api/inventory/out`、`/api/finance/payments` 支持；不带则行为不变。

- 记录在表 `sys_idempotency`，`UNIQUE (user_name, idem_key)`：key 按登录用户隔离，最长 128 个字符（空白或超长 400）。
- 参数是否相同：对校验后的请求 DTO 做规范化 JSON（字段按名称排序，`BigDecimal` 去掉末尾 0，`"1.0"` 与 `"1"` 相同）再加上接口名取 SHA-256。
- 同 key 同参数且已成功：返回原单据（不重复建单、不重复记账）。**重放返回的是单据的当前状态**（按 id 重新查询 detail），不是第一次响应的快照：期间单据被编辑、提交、收货等，重放看到的是变化后的内容和 version（#22-10）；同 key 不同参数（或用在别的接口）：**409 / 40902**；上一次还在处理：**409 / 40904**。
- 请求失败（含 400、503/50301、500）：占位记录删除，key 不被占用，用同一 key 重试会正常创建且只建一张。
- 同一用户同一 key 并发提交只会生成一张单据（唯一索引占位）。
- 记录保留 24 小时，过期的 key 视为新 key；每小时清理一次过期记录。占位时遇到过期记录会先删除再占位，删除失败时返回 **409 / 40904**（稍后重试），不会拿过期记录比对（#22-11）。
- PROCESSING 记录有租期 `erp.idempotency.processing-lease-seconds`（默认 60 秒）：租期内同 key 返回 **409 / 40904**；租期过后：
  1. 先按单据上的 `(idem_user, idem_key)` 查是否已有提交的单据——有则把记录补成 DONE 并重放（单据与幂等键同事务写入，崩溃也不会丢）；
  2. 没有则条件更新接管（只改过期租期，只有一个请求能改到），再创建。
  慢但还活着的原请求与接管者并发时，单据表 `UNIQUE (idem_user, idem_key)` 保证只落一张；后提交的一方撞唯一键回滚，查到已提交的单据后重放同一张。
- 单据列 `idem_user` / `idem_key`（采购单、销售单、库存流水、收付款）：未带 `Idempotency-Key` 时为 null（唯一索引允许多个 null）。已有 MySQL 库由启动时的 `SchemaUpgrade` 自动 `ADD COLUMN` / `ADD UNIQUE KEY`（幂等），README 也给了手工 DDL。
- 核销接口的 `requestNo` 幂等不变。

已有 MySQL 库手工建表 / 补列（启动时 `SchemaUpgrade` 也会幂等执行）：
```sql
CREATE TABLE IF NOT EXISTS sys_idempotency (
  id BIGINT AUTO_INCREMENT PRIMARY KEY, user_name VARCHAR(64) NOT NULL, idem_key VARCHAR(128) NOT NULL,
  req_hash CHAR(64) NOT NULL, status VARCHAR(16) NOT NULL, result_id BIGINT,
  created_at DATETIME NOT NULL, expires_at DATETIME NOT NULL, owner_token VARCHAR(64), lease_until DATETIME,
  UNIQUE KEY uk_idem_user_key (user_name, idem_key), INDEX idx_idem_expires (expires_at));
ALTER TABLE sys_idempotency ADD COLUMN owner_token VARCHAR(64), ADD COLUMN lease_until DATETIME; -- 已有表
ALTER TABLE pur_order ADD COLUMN idem_user VARCHAR(64), ADD COLUMN idem_key VARCHAR(128), ADD UNIQUE KEY uk_pur_idem (idem_user, idem_key);
ALTER TABLE sal_order ADD COLUMN idem_user VARCHAR(64), ADD COLUMN idem_key VARCHAR(128), ADD UNIQUE KEY uk_sal_idem (idem_user, idem_key);
ALTER TABLE inv_stock_txn ADD COLUMN idem_user VARCHAR(64), ADD COLUMN idem_key VARCHAR(128), ADD UNIQUE KEY uk_txn_idem (idem_user, idem_key);
ALTER TABLE fin_payment ADD COLUMN idem_user VARCHAR(64), ADD COLUMN idem_key VARCHAR(128), ADD UNIQUE KEY uk_pay_idem (idem_user, idem_key);
```

**日志**：回滚失败且没有原始异常时，整个请求只记一条简短 ERROR（TxRetry 记过的，全局处理器不再记）；堆栈只在 `GlobalExceptionHandler` 的 DEBUG 级别输出。
