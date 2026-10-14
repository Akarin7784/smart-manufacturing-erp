package com.akarin.erp;

import com.akarin.erp.auth.mapper.SysPermissionMapper;
import com.akarin.erp.auth.mapper.SysRoleMapper;
import com.akarin.erp.auth.mapper.SysUserMapper;
import com.akarin.erp.mdm.mapper.MaterialMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.akarin.erp.auth.service.UserAdminService;
import com.akarin.erp.auth.service.RoleAdminService;
import com.akarin.erp.mdm.service.MaterialService;
import com.akarin.erp.mdm.entity.Material;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;


import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真正复现并发窗口：服务里 selectById 读到记录之后、执行更新之前，用 JDBC 把这一行的 version + 1
 * （模拟另一个会话在这一瞬间改了姓名）。修复前 updateById 会带着旧 version 更新到 0 行，操作被静默吞掉。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentVersionWindowIntegrationTest extends SysTestSupport {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txm;
    @Autowired UserAdminService users;
    @Autowired SysUserMapper userMapper;
    @Autowired RoleAdminService roles;
    @Autowired MaterialService materials;

    /** 下一次 selectById(armedId) 之后要 bump version 的表；null 表示不触发 */
    static volatile String armedTable;
    static volatile long armedId;
    static volatile int fired;
    /** 非 null 时代替默认的“version + 1”：在独立事务里执行并提交（另一个会话的真实操作） */
    static volatile Runnable armedAction;
    static volatile JdbcTemplate hookJdbc;
    static volatile TransactionTemplate hookTx;

    /** 把 4 个 mapper 包一层 JDK 代理：selectById 返回之后、调用方拿到实体去更新之前，插入一次 version + 1 */
    @TestConfiguration
    static class WindowHook {
        private static final Map<Class<?>, String> TABLES = Map.of(
                SysUserMapper.class, "sys_user", SysRoleMapper.class, "sys_role",
                SysPermissionMapper.class, "sys_permission", MaterialMapper.class, "mdm_material");

        @Bean static BeanPostProcessor versionWindowHook() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    for (var e : TABLES.entrySet()) {
                        if (!e.getKey().isInstance(bean)) continue;
                        String table = e.getValue();
                        return Proxy.newProxyInstance(e.getKey().getClassLoader(), new Class<?>[]{e.getKey()}, (px, m, args) -> {
                            Object r;
                            try { r = m.invoke(bean, args); } catch (InvocationTargetException ex) { throw ex.getCause(); }
                            if (m.getName().equals("selectById") && table.equals(armedTable) && args[0] != null
                                    && Long.parseLong(args[0].toString()) == armedId) {
                                armedTable = null;
                                // 另起一个事务（独立连接）提交，模拟另一个会话在这一瞬间改了这行
                                Runnable act = armedAction; armedAction = null;
                                fired = hookTx.execute(st -> {
                                    if (act != null) { act.run(); return 1; }
                                    return hookJdbc.update("UPDATE " + table + " SET version = version + 1 WHERE id = ?", armedId);
                                });
                            }
                            return r;
                        });
                    }
                    return bean;
                }
            };
        }
    }

    private static void arm(String table, long id) { armedAction = null; armedTable = table; armedId = id; fired = 0; }
    /** 下一次 selectById(id) 之后，先让 action 在独立事务里提交，再让调用方继续 */
    private static void armAction(String table, long id, Runnable action) { arm(table, id); armedAction = action; }

    @BeforeEach void hooks() {
        hookJdbc = jdbc;
        hookTx = new TransactionTemplate(txm);
        hookTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach void disarm() { armedTable = null; armedAction = null; }

    private int loginCode(String username, String pw) throws Exception {
        return code(null, "POST", "/api/auth/login", "{\"username\":\"" + username + "\",\"password\":\"" + pw + "\"}");
    }

    private long dbVersion(String table, long id) {
        return jdbc.queryForObject("SELECT version FROM " + table + " WHERE id = ?", Long.class, id);
    }

    @Test void disableTakesEffectWhenVersionChangesBetweenReadAndUpdate() throws Exception {
        User u = user();
        String url = "/api/sys/users/" + u.id();
        String tok = u.token(this);
        arm("sys_user", u.id());
        ok(admin, "PUT", url + "/status", "{\"enabled\":false}");
        assertThat(fired).as("钩子确实在读取和更新之间改了 version").isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE id=?", Integer.class, u.id())).isZero();
        assertThat(loginCode(u.username(), PW)).isEqualTo(403);
        assertThat(code(tok, "GET", "/api/auth/me", null)).as("旧 token 失效").isEqualTo(401);
    }

    @Test void resetPasswordTakesEffectWhenVersionChangesBetweenReadAndUpdate() throws Exception {
        User u = user();
        String tok = u.token(this);
        arm("sys_user", u.id());
        ok(admin, "PUT", "/api/sys/users/" + u.id() + "/password", "{\"password\":\"newpass123\"}");
        assertThat(fired).isEqualTo(1);
        assertThat(loginCode(u.username(), "newpass123")).isZero();
        assertThat(loginCode(u.username(), PW)).isEqualTo(401);
        assertThat(code(tok, "GET", "/api/auth/me", null)).isEqualTo(401);
    }

    @Test void assignRolesBumpsTokenVersionWhenVersionChangesBetweenReadAndUpdate() throws Exception {
        User u = user();
        Role r = role("CW");
        String tok = u.token(this);
        arm("sys_user", u.id());
        ok(admin, "PUT", "/api/sys/users/" + u.id() + "/roles", "{\"ids\":[" + r.id() + "]}");
        assertThat(fired).isEqualTo(1);
        assertThat(code(tok, "GET", "/api/auth/me", null)).as("改角色后旧 token 失效").isEqualTo(401);
    }

    @Test void softDeletesTakeEffectWhenVersionChangesBetweenReadAndUpdate() throws Exception {
        User u = user();
        arm("sys_user", u.id());
        ok(admin, "DELETE", "/api/sys/users/" + u.id(), null);
        assertThat(fired).isEqualTo(1);
        assertThat(code(admin, "GET", "/api/sys/users/" + u.id(), null)).isEqualTo(404);
        assertThat(loginCode(u.username(), PW)).isNotZero();

        Role r = role("CD");
        arm("sys_role", r.id());
        ok(admin, "DELETE", "/api/sys/roles/" + r.id(), null);
        assertThat(fired).isEqualTo(1);
        assertThat(code(admin, "GET", "/api/sys/roles/" + r.id(), null)).isEqualTo(404);

        long pid = ok(admin, "POST", "/api/sys/permissions", "{\"code\":\"" + uniq("cw:p") + "\",\"name\":\"x\"}").path("id").asLong();
        arm("sys_permission", pid);
        ok(admin, "DELETE", "/api/sys/permissions/" + pid, null);
        assertThat(fired).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted FROM sys_permission WHERE id=?", Integer.class, pid)).isEqualTo(1);

        long mid = mdm(admin, "materials", "CW");
        arm("mdm_material", mid);
        ok(admin, "DELETE", "/api/mdm/materials/" + mid, null);
        assertThat(fired).isEqualTo(1);
        assertThat(code(admin, "GET", "/api/mdm/materials/" + mid, null)).isEqualTo(404);
    }

    @Test void versionedUpdateStillConflictsInTheSameWindow() throws Exception {
        // 对照组：带客户端 version 的普通编辑在同一窗口里必须 40900，证明钩子确实制造了冲突
        User u = user();
        String url = "/api/sys/users/" + u.id();
        long v = ver(admin, url);
        arm("sys_user", u.id());
        assertThat(code(admin, "PUT", url, "{\"realName\":\"x\",\"version\":" + v + "}")).isEqualTo(40900);
        assertThat(fired).isEqualTo(1);
        assertThat(dbVersion("sys_user", u.id())).isEqualTo(v + 1);
    }

    // ---------- 反方向：改名读出之后、写回之前，安全操作先提交 ----------

    private int rename(String url, long v) throws Exception {
        return code(admin, "PUT", url, "{\"realName\":\"并发改名\",\"version\":" + v + "}");
    }

    @Test void disableCommittedInsideRenameWindowIsNotOverwritten() throws Exception {
        User u = user();
        String url = "/api/sys/users/" + u.id();
        String tok = u.token(this);
        long v = ver(admin, url);
        armAction("sys_user", u.id(), () -> users.setEnabled(u.id(), false));
        assertThat(rename(url, v)).as("改名拿着旧 version → 40900").isEqualTo(40900);
        assertThat(fired).isEqualTo(1);
        assertThat(ok(admin, "GET", url, null).path("enabled").asBoolean()).isFalse();
        assertThat(loginCode(u.username(), PW)).isEqualTo(403);
        assertThat(code(tok, "GET", "/api/auth/me", null)).isEqualTo(401);
        assertThat(ver(admin, url)).as("停用递增了 version").isEqualTo(v + 1);
    }

    @Test void resetPasswordCommittedInsideRenameWindowIsNotOverwritten() throws Exception {
        User u = user();
        String url = "/api/sys/users/" + u.id();
        String tok = u.token(this);
        long v = ver(admin, url);
        armAction("sys_user", u.id(), () -> users.resetPassword(u.id(), "newpass123"));
        assertThat(rename(url, v)).isEqualTo(40900);
        assertThat(fired).isEqualTo(1);
        assertThat(loginCode(u.username(), "newpass123")).isZero();
        assertThat(loginCode(u.username(), PW)).isEqualTo(401);
        assertThat(code(tok, "GET", "/api/auth/me", null)).isEqualTo(401);
    }

    @Test void assignRolesCommittedInsideRenameWindowDoesNotReviveOldToken() throws Exception {
        User u = user();
        Role r = role("RW");
        String url = "/api/sys/users/" + u.id();
        String tok = u.token(this);
        long v = ver(admin, url);
        armAction("sys_user", u.id(), () -> users.assignRoles(u.id(), java.util.List.of(r.id())));
        assertThat(rename(url, v)).isEqualTo(40900);
        assertThat(fired).isEqualTo(1);
        assertThat(code(tok, "GET", "/api/auth/me", null)).as("旧 token 不能被改名写回而复活").isEqualTo(401);
        assertThat(ok(admin, "GET", url, null).path("roleCodes").toString()).contains(r.code());
    }

    @Test void deleteCommittedInsideRenameWindowIsNotUndone() throws Exception {
        User u = user();
        String url = "/api/sys/users/" + u.id();
        long v = ver(admin, url);
        armAction("sys_user", u.id(), () -> users.delete(u.id()));
        assertThat(rename(url, v)).isIn(40900, 404);
        assertThat(fired).isEqualTo(1);
        assertThat(code(admin, "GET", url, null)).isEqualTo(404);
        assertThat(loginCode(u.username(), PW)).isNotZero();

        Role r = role("RD");
        String rurl = "/api/sys/roles/" + r.id();
        long rv = ver(admin, rurl);
        armAction("sys_role", r.id(), () -> roles.delete(r.id()));
        assertThat(code(admin, "PUT", rurl, "{\"name\":\"x\",\"version\":" + rv + "}")).isIn(40900, 404);
        assertThat(code(admin, "GET", rurl, null)).isEqualTo(404);
    }

    @Test void masterDataStatusChangeInsideRenameWindowIsNotOverwritten() throws Exception {
        long id = mdm(admin, "materials", "RW");
        String url = "/api/mdm/materials/" + id;
        long v = ver(admin, url);
        armAction("mdm_material", id, () -> {
            Material m = new Material(); m.setStatus(0); m.setVersion((int) v);
            materials.update(id, m);
        });
        assertThat(code(admin, "PUT", url, "{\"name\":\"改名\",\"version\":" + v + "}")).isEqualTo(40900);
        assertThat(fired).isEqualTo(1);
        JsonNode d = ok(admin, "GET", url, null);
        assertThat(d.path("status").asInt()).as("停用没有被改名覆盖").isZero();
        assertThat(d.path("version").asLong()).isEqualTo(v + 1);
    }

    /** 多线程压力：一个线程不停“查 version → 改名”，另一个线程停用 / 改密；每轮最终都必须生效 */
    @Test void stressRenameVersusDisableAndResetPassword() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                User u = user();
                String url = "/api/sys/users/" + u.id();
                String tok = u.token(this);
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger renamed = new AtomicInteger();
                Future<?> renamer = pool.submit(() -> {
                    go.await();
                    for (int i = 0; i < 8; i++) if (rename(url, ver(admin, url)) == 0) renamed.incrementAndGet();
                    return null;
                });
                Future<?> security = pool.submit(() -> {
                    go.await();
                    ok(admin, "PUT", url + "/password", "{\"password\":\"newpass123\"}");
                    ok(admin, "PUT", url + "/status", "{\"enabled\":false}");
                    return null;
                });
                go.countDown();
                renamer.get(30, TimeUnit.SECONDS); security.get(30, TimeUnit.SECONDS);
                assertThat(jdbc.queryForObject("SELECT status FROM sys_user WHERE id=?", Integer.class, u.id())).as("round " + round).isZero();
                assertThat(code(tok, "GET", "/api/auth/me", null)).as("round " + round).isEqualTo(401);
                ok(admin, "PUT", url + "/status", "{\"enabled\":true}");
                assertThat(loginCode(u.username(), "newpass123")).as("round " + round).isZero();
            }
        } finally { pool.shutdownNow(); }
    }

    /** bumpTokenVersionByRole 只更新未软删除的用户：残留的 sys_user_role 关联不会让已删除用户的 version / token_version 变化 */
    @Test void bumpTokenVersionByRoleSkipsSoftDeletedUsers() throws Exception {
        Role r = role("BD");
        User live = user(r), gone = user(r);
        ok(admin, "DELETE", "/api/sys/users/" + gone.id(), null);
        // 删除用户时会清掉关联；这里手工补回一条残留关联，模拟脏数据
        jdbc.update("INSERT INTO sys_user_role(user_id, role_id) VALUES (?, ?)", gone.id(), r.id());
        String sql = "SELECT version, token_version FROM sys_user WHERE id = ?";
        Map<String, Object> goneBefore = jdbc.queryForMap(sql, gone.id()), liveBefore = jdbc.queryForMap(sql, live.id());
        assertThat(userMapper.bumpTokenVersionByRole(r.id())).as("只更新 1 个未删除用户").isEqualTo(1);
        assertThat(jdbc.queryForMap(sql, gone.id())).as("已软删除用户不被更新").isEqualTo(goneBefore);
        Map<String, Object> liveAfter = jdbc.queryForMap(sql, live.id());
        assertThat(((Number) liveAfter.get("version")).longValue()).isEqualTo(((Number) liveBefore.get("version")).longValue() + 1);
        assertThat(((Number) liveAfter.get("token_version")).longValue()).isEqualTo(((Number) liveBefore.get("token_version")).longValue() + 1);
    }
}
