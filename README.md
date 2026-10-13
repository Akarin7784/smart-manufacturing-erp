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
| erp-finance | 应收/应付单骨架（实体与查询） |
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
- 按单据类型（`PURCHASE_ORDER`、`SALES_ORDER`）配置流程：节点按顺序执行，每个节点指定审批角色 `roleCode`，审批方式是 `ANY`（或签）或 `ALL`（会签，该角色下所有启用用户都要同意）。可选 `minAmount`：单据金额大于它时才进入这个节点。
- 提交时按金额选出要走的节点，并把节点快照保存下来。存在审批中的实例时，不能修改或删除该单据类型的流程。
- 只有当前节点的审批人能处理，否则返回 403；同一个人在同一节点只能处理一次。任一节点驳回，单据回到 **DRAFT**，可以修改后重新提交。
- 没有配置流程或流程已停用时，兼容原来的单级审批：持有 `workflow:approve` 的用户都能审批。
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
- `/api/finance/bills`

## 当前进度
- [x] 多模块骨架、统一响应/异常、审计字段、逻辑删除、分页
- [x] JWT 登录 + RBAC 权限点鉴权
- [x] 主数据 CRUD
- [x] 采购/销售订单 + 单级审批 + 审批事件驱动状态
- [x] 库存出入库、流水、乐观锁防超扣

## 后续规划
1. 财务：收货/发货自动生成应付/应收，收付款核销，往来对账
2. 操作日志（关键写操作审计）
3. 采购：采购申请 → 订单、分批到货、退货；销售：发货单、部分发货、退货
3. 库存：调拨、盘点、库存冻结/预占、批次与库位、月结
4. 审批流：流程定义（多节点、会签/或签、条件分支）、转办与审批记录
5. 用户/角色/菜单管理接口、数据权限（按组织/仓库）、操作日志
6. Redis：单号号段、权限缓存、登录限流；接口文档（springdoc）、Flyway 迁移
7. 单元/集成测试（Testcontainers）、CI、Docker 镜像；与 MES 项目对接（生产领料/完工入库）
