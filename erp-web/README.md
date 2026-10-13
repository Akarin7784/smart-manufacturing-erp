# erp-web

ERP 前端：Vue 3 + Vite + Element Plus + Pinia + Vue Router + axios，测试使用 Vitest + @vue/test-utils。

## 运行

```bash
cd erp-web
npm install
npm run dev            # http://localhost:5173 ，/api 代理到 http://localhost:8080
npm run build
npm run test
npm run test:coverage
```

开发代理目标由环境变量 `VITE_PROXY_TARGET` 指定，默认 `http://localhost:8080`，例如：`VITE_PROXY_TARGET=http://localhost:8081 npm run dev`。

后端（H2）：`mvn -pl erp-boot -am spring-boot:run`，默认账号 `admin / admin123`。Node 22.x（与 CI `actions/setup-node` 的 node-version 22 一致，见 `.nvmrc`）。Element Plus 通过 unplugin-vue-components / unplugin-auto-import 按需引入。

## 目录

- `src/utils/request.js`：axios 实例与拦截器
- `src/stores/auth.js`：token / 用户（Pinia + localStorage）
- `src/router/index.js`：路由与登录守卫
- `src/layout/MainLayout.vue`：侧边菜单 + 顶栏（用户、退出登录）
- `src/views/LoginView.vue`：登录页（`redirect` 只接受以 `/` 开头且不以 `//` 开头的站内路径，否则回到 `/`）
- `src/views/mdm/MdmListView.vue` + `src/config/mdm.js`：主数据通用列表页（物料/客户/供应商/仓库）

## 接口约定与错误处理

- 响应体统一 `{code, msg, data}`，`code=0` 成功，拦截器直接返回 `data`。
- **HTTP 状态码表示类别，`body.code` 表示业务子码**；前端只按码判断，不匹配 `msg` 文本。常量见 `src/api/errorCodes.js`：

| HTTP | code | 含义 | 前端提示 |
|---|---|---|---|
| 400 | 40001 | 编码重复（含已删除） | 编码已存在（含已删除） |
| 409 | 40900 | 乐观锁/并发冲突 | 数据已被修改，请刷新重试 |
| 409 | 40901 | 被单据引用，不能删除 | 该数据已被单据引用，不能删除，可改为停用 |
| 409 | 40902 | 同一请求 ID 参数不同的重复提交 | 请勿重复提交 |
| 401 | 401 | 未登录/登录失效 | 清 token 并跳登录（登录接口本身只提示原因） |
| 403 | 403 | 无权限 | 没有权限执行此操作（登录接口显示原因，如账号已停用） |
| 409 | 409 | 其他冲突 | 数据已被修改，请刷新重试 |
| 400/404 | 同状态 | 参数错误/不存在 | 显示后端 `msg` |
| 5xx | — | 系统异常 | 系统异常，请稍后重试 |

- 被拒绝的 Promise 是 `BizError`：`e.code` 为业务子码（如 40901），`e.status` 为 HTTP 类别（如 409），`e.message` 为提示文案。调用方传 `{ silent: true }` 可关闭自动提示，自行处理。
- **兼容旧后端**：HTTP 200 但 `body.code ≠ 0` 时视为失败，类别由 code 推导（40001 → 400）。

## 用到的后端接口

- `POST /api/auth/login` `{username,password}` → `{token}`
- `GET /api/auth/me` → `{username, authorities[]}`
- `GET /api/mdm/{materials|customers|suppliers|warehouses}?page&size&keyword` → MyBatis-Plus Page `{records,total,size,current,pages}`（keyword 模糊匹配编码/名称）
