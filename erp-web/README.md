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

后端（H2）：`mvn -pl erp-boot -am spring-boot:run`，默认账号 `admin / admin123`。Node ≥ 20。

## 目录

- `src/utils/request.js`：axios 实例与拦截器
- `src/stores/auth.js`：token / 用户（Pinia + localStorage）
- `src/router/index.js`：路由与登录守卫
- `src/layout/MainLayout.vue`：侧边菜单 + 顶栏（用户、退出登录）
- `src/views/LoginView.vue`：登录页
- `src/views/mdm/MdmListView.vue` + `src/config/mdm.js`：主数据通用列表页（物料/客户/供应商/仓库）

## 接口约定与错误处理

- 响应体统一 `{code, msg, data}`，成功时拦截器直接返回 `data`。
- **成功/失败以 HTTP 状态码为准**（读取 `error.response.status`）：
  - 401：清除 token，跳转登录页（登录接口本身的 401 只提示“用户名或密码错误”）
  - 403：提示“没有权限执行此操作”
  - 409：提示“数据已被他人修改，请刷新后重试”
  - 400/404：提示后端 `msg`；5xx：提示“系统异常”
- **兼容旧后端**：HTTP 200 但 `body.code ≠ 0` 时，按 `body.code` 走同样的处理。

## 用到的后端接口

- `POST /api/auth/login` `{username,password}` → `{token}`
- `GET /api/auth/me` → `{username, authorities[]}`
- `GET /api/mdm/{materials|customers|suppliers|warehouses}?page&size&keyword` → MyBatis-Plus Page `{records,total,size,current,pages}`（keyword 模糊匹配编码/名称）
