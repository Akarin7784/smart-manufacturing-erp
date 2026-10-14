package com.akarin.erp;

import com.akarin.erp.auth.mapper.SysPermissionMapper;
import com.akarin.erp.auth.mapper.SysRoleMapper;
import com.akarin.erp.auth.mapper.SysUserMapper;
import com.akarin.erp.mdm.mapper.MaterialMapper;
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

    /** 下一次 selectById(armedId) 之后要 bump version 的表；null 表示不触发 */
    static volatile String armedTable;
    static volatile long armedId;
    static volatile int fired;
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
                                fired = hookTx.execute(st -> hookJdbc.update("UPDATE " + table + " SET version = version + 1 WHERE id = ?", armedId));
                            }
                            return r;
                        });
                    }
                    return bean;
                }
            };
        }
    }

    private static void arm(String table, long id) { armedTable = table; armedId = id; fired = 0; }

    @BeforeEach void hooks() {
        hookJdbc = jdbc;
        hookTx = new TransactionTemplate(txm);
        hookTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @AfterEach void disarm() { armedTable = null; }

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
}
