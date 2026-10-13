# ERP —— 传统多模块 ERP 系统（Java）

面向中小制造/贸易企业的进销存 + 轻财务 ERP：主数据、采购、销售、库存、应收应付，配套 RBAC 权限与审批流。
定位是「朴实、业务闭环完整」：单据流转、出入库与库存流水、审批驱动状态机。

## 技术栈
Java 17 · Spring Boot 3.3 · Spring Security + JWT · MyBatis-Plus 3.5 · MySQL 8（本地可用 H2）· Redis（已预留）· Maven 多模块

## 模块
| 模块 | 职责 |
|---|---|
| erp-common | 统一响应 `R`、业务异常与全局异常处理、`BaseEntity`（审计字段/逻辑删除）、MyBatis-Plus 分页与乐观锁、单号生成、审批事件 |
| erp-auth | 登录签发 JWT、用户-角色-权限（RBAC）、`@PreAuthorize` 方法级鉴权 |
| erp-mdm | 主数据：物料、客户、供应商、仓库（分页/增删改查，编码唯一校验） |
| erp-workflow | 审批流骨架：审批实例、通过/驳回，通过 Spring 事件回调业务模块 |
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
默认账号 `admin / admin123`（可用 `ERP_ADMIN_PASSWORD` 覆盖），JWT 密钥用 `ERP_JWT_SECRET` 覆盖。

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

## 主要接口
- `POST /api/auth/login`、`GET /api/auth/me`
- `/api/mdm/{materials|customers|suppliers|warehouses}`：GET 分页（`keyword`）、GET/{id}、POST、PUT/{id}、DELETE/{id}
- `/api/purchase/orders`：GET、POST、GET/{id}、POST/{id}/submit、POST/{id}/receive
- `/api/sales/orders`：GET、POST、GET/{id}、POST/{id}/submit、POST/{id}/ship
- `/api/workflow/approvals/pending`、`POST /api/workflow/approvals/{id}/decide`
- `/api/inventory/stocks`、`/api/inventory/txns`、`POST /api/inventory/in`、`POST /api/inventory/out`
- `GET /api/finance/bills`（`billType`/`partnerId`/`status`/`sourceBizNo`）、`GET /api/finance/bills/{id}`
- `POST /api/finance/payments` 收付款登记，`GET /api/finance/payments`
- `POST /api/finance/settlements` 核销，`GET /api/finance/settlements?billId=`
- `GET /api/finance/reconciliation?billType=AR|AP&partnerId=` 对账汇总

## 约定
- 健康检查：`GET /actuator/health`，不需要登录，只暴露 health 端点。
- 响应体统一为 `{code,msg,data}`；出错时 HTTP 状态表示大类，`code` 是细分码，见下方错误码表。
- 金额参数要求 ≥0.01、最多 2 位小数；服务层先按 HALF_UP 四舍五入，再做校验。

## 错误码
响应体 `code` 是细分码，HTTP 状态是大类（定义在 `erp-common` 的 `ErrorCode` 枚举里）。通用错误的 `code` 就等于 HTTP 状态码；业务细分码按 HTTP 状态 × 100 + 序号编排。

| HTTP 状态 | code | 含义 | 触发场景 |
|---|---|---|---|
| 400 | 400 | 参数或业务校验失败 | 所有接口的 `@Valid` 校验失败（例如 `POST /api/finance/settlements` 金额 <0.01 或超过 2 位小数），请求体格式错误、参数类型错误；业务规则不满足：`POST /api/purchase/orders/{id}/receive`、`/api/sales/orders/{id}/ship` 时订单状态不是 APPROVED，`POST /api/inventory/out` 或发货时库存不足，`POST /api/finance/settlements` 超额核销、核销已核销的单据、收付款类型或往来单位不匹配 |
| 400 | 40001 | 编码已存在（msg 以"编码已存在"开头） | `POST /api/mdm/{materials,customers,suppliers,warehouses}` 时编码重复 |
| 401 | 401 | 未登录或登录已失效 | 不带 token 或 token 无效时访问除 `/api/auth/login`、`/actuator/health` 以外的接口；`POST /api/auth/login` 用户名或密码错误 |
| 403 | 403 | 无权限 | 缺少对应权限点，例如没有 `finance:read` 时调用 `GET /api/finance/*`，没有 `finance:write` 时调用 `POST /api/finance/payments`、`/settlements`；登录时账号已停用 |
| 404 | 404 | 资源不存在 | 路径不存在；`GET /api/finance/bills/{id}` 查不到；核销时单据或收付款不存在 |
| 405 | 405 | 请求方法不支持 | 例如 `PUT /api/finance/reconciliation` |
| 409 | 40900 | 并发冲突（乐观锁或状态已变化） | `POST /api/finance/settlements` 时单据或收付款的版本冲突，或同一请求号被并发提交；`POST /api/purchase/orders/{id}/receive`、`/api/sales/orders/{id}/ship` 被并发重复调用；数据库唯一键冲突 |
| 409 | 40901 | 主数据已被引用，不能删除 | `DELETE /api/mdm/*/{id}`（引用检查在 P2 实现） |
| 409 | 40902 | 幂等请求号复用但参数不同 | `POST /api/finance/settlements` 时同一个 `requestNo` 对应的 billId、paymentId 或 amount 和第一次不一致 |
| 500 | 500 | 系统异常 | 未预期的异常 |

## 财务闭环
- 权限：查询类接口需要 `finance:read`，收付款登记和核销需要 `finance:write`（admin 已预置这两个权限）。
- 收货和发货用带状态条件的更新（`APPROVED → RECEIVED/SHIPPED`）。并发重复提交时只有一个请求成功，其余返回 409；顺序重复提交返回 400。
- 采购收货 / 销售发货完成后发布 `StockBizCompletedEvent`，财务同步监听（同一事务）生成 **应付 AP / 应收 AR**；金额 = Σ 数量×单价（BigDecimal，2 位，HALF_UP），按来源单号唯一，重复事件不重复生成。
- 收付款：`PAYMENT` 付款（供应商）只能核销 AP，`RECEIPT` 收款（客户）只能核销 AR，往来单位必须一致。
- 核销状态机：`OPEN 未核销 → PARTIAL 部分核销 → SETTLED 已核销`；核销金额不得超过单据余额，也不得超过收付款未核销余额。
- 幂等：`requestNo` 唯一。同号同参数重放返回原结果；同号不同参数返回 409。
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
mvn verify   # 单元测试 + H2 集成测试 + JaCoCo 聚合报告
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
2. 采购：采购申请 → 订单、分批到货、退货；销售：发货单、部分发货、退货
3. 库存：调拨、盘点、库存冻结/预占、批次与库位、月结
4. 审批流：流程定义（多节点、会签/或签、条件分支）、转办与审批记录
5. 用户/角色/菜单管理接口、数据权限（按组织/仓库）、操作日志
6. Redis：单号号段、权限缓存、登录限流；接口文档（springdoc）、Flyway 迁移
7. 单元/集成测试（Testcontainers）、CI、Docker 镜像；与 MES 项目对接（生产领料/完工入库）
