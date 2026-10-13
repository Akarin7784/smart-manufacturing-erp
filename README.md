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

## 主要接口
- `POST /api/auth/login`、`GET /api/auth/me`
- `/api/mdm/{materials|customers|suppliers|warehouses}`：GET 分页（`keyword`）、GET/{id}、POST、PUT/{id}、DELETE/{id}
- `/api/purchase/orders`：GET、POST、GET/{id}、POST/{id}/submit、POST/{id}/receive
- `/api/sales/orders`：GET、POST、GET/{id}、POST/{id}/submit、POST/{id}/ship
- `/api/workflow/approvals/pending`、`POST /api/workflow/approvals/{id}/decide`
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
2. 采购：采购申请 → 订单、分批到货、退货；销售：发货单、部分发货、退货
3. 库存：调拨、盘点、库存冻结/预占、批次与库位、月结
4. 审批流：流程定义（多节点、会签/或签、条件分支）、转办与审批记录
5. 用户/角色/菜单管理接口、数据权限（按组织/仓库）、操作日志
6. Redis：单号号段、权限缓存、登录限流；接口文档（springdoc）、Flyway 迁移
7. 单元/集成测试（Testcontainers）、CI、Docker 镜像；与 MES 项目对接（生产领料/完工入库）
