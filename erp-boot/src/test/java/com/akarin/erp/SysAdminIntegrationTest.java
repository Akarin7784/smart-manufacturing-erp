package com.akarin.erp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
class SysAdminIntegrationTest extends SysTestSupport {

    @Test void createUserAssignRoleAndPermissionsTakeEffect() throws Exception {
        Role viewer = role("VIEW", "sys:read");
        User u = user(viewer);
        JsonNode view = ok(admin, "GET", "/api/sys/users/" + u.id(), null);
        assertThat(view.path("roleCodes").get(0).asText()).isEqualTo(viewer.code());
        assertThat(view.has("password")).as("不返回密码").isFalse();
        assertThat(view.path("enabled").asBoolean()).isTrue();
        String t = u.token(this);
        assertThat(code(t, "GET", "/api/sys/users?keyword=" + u.username(), null)).isZero();
        assertThat(ok(t, "GET", "/api/sys/users?keyword=" + u.username(), null).path("total").asInt()).isEqualTo(1);
        assertThat(code(t, "POST", "/api/sys/roles", "{\"code\":\"X\",\"name\":\"x\"}")).as("无 sys:write").isEqualTo(403);
        assertThat(code(t, "GET", "/api/sys/roles", null)).isZero();
        assertThat(code(t, "GET", "/api/sys/roles/" + viewer.id(), null)).isZero();

        // 调整角色后旧 token 失效，重新登录获得新权限
        Role writer = role("WRITE", "sys:read", "sys:write");
        ok(admin, "PUT", "/api/sys/users/" + u.id() + "/roles", "{\"ids\":[" + writer.id() + "]}");
        assertThat(code(t, "GET", "/api/sys/users", null)).isEqualTo(401);
        String t2 = u.token(this);
        assertThat(code(t2, "PUT", "/api/sys/users/" + u.id(), "{\"realName\":\"新名字\"}")).isZero();
        assertThat(ok(admin, "GET", "/api/sys/users/" + u.id(), null).path("realName").asText()).isEqualTo("新名字");

        // 修改角色权限点同样使持有者 token 失效
        ok(admin, "PUT", "/api/sys/roles/" + writer.id() + "/permissions", "{\"ids\":[" + permIds.get("sys:read") + "]}");
        assertThat(code(t2, "GET", "/api/sys/users", null)).isEqualTo(401);
        assertThat(code(u.token(this), "PUT", "/api/sys/users/" + u.id(), "{\"realName\":\"x\"}")).isEqualTo(403);
    }

    @Test void disableInvalidatesTokenAndBlocksLogin() throws Exception {
        User u = user(role("R", "sys:read"));
        String t = u.token(this);
        ok(admin, "PUT", "/api/sys/users/" + u.id() + "/status", "{\"enabled\":false}");
        assertThat(code(t, "GET", "/api/sys/users", null)).isEqualTo(401);
        assertThat(code(null, "POST", "/api/auth/login", "{\"username\":\"" + u.username() + "\",\"password\":\"" + PW + "\"}")).isEqualTo(403);
        ok(admin, "PUT", "/api/sys/users/" + u.id() + "/status", "{\"enabled\":true}");
        assertThat(code(t, "GET", "/api/sys/users", null)).as("旧 token 仍失效").isEqualTo(401);
        assertThat(code(u.token(this), "GET", "/api/sys/users", null)).isZero();
    }

    @Test void passwordIsBcryptAndResetInvalidatesToken() throws Exception {
        User u = user(role("R", "sys:read"));
        String t = u.token(this);
        ok(admin, "PUT", "/api/sys/users/" + u.id() + "/password", "{\"password\":\"newpass99\"}");
        assertThat(code(t, "GET", "/api/sys/users", null)).isEqualTo(401);
        assertThat(code(null, "POST", "/api/auth/login", "{\"username\":\"" + u.username() + "\",\"password\":\"" + PW + "\"}")).isEqualTo(401);
        assertThat(login(u.username(), "newpass99")).isNotBlank();
        assertThat(code(admin, "PUT", "/api/sys/users/" + u.id() + "/password", "{\"password\":\"123\"}")).as("密码过短").isEqualTo(400);
    }

    @Test void cannotDeleteOrDisableSelfOrLastAdmin() throws Exception {
        assertThat(code(admin, "DELETE", "/api/sys/users/1", null)).isEqualTo(400);
        assertThat(call(admin, "PUT", "/api/sys/users/1/status", "{\"enabled\":false}").path("msg").asText()).contains("当前登录用户");
        assertThat(call(admin, "PUT", "/api/sys/users/1/roles", "{\"ids\":[]}").path("msg").asText()).contains("最后一个管理员");

        // 非管理员但有 sys:write 的运维用户：删除/停用唯一管理员被拒
        User ops = user(role("OPS", "sys:read", "sys:write"));
        String opsT = ops.token(this);
        assertThat(call(opsT, "DELETE", "/api/sys/users/1", null).path("msg").asText()).contains("最后一个管理员");
        assertThat(call(opsT, "PUT", "/api/sys/users/1/status", "{\"enabled\":false}").path("msg").asText()).contains("最后一个管理员");

        // 有第二个启用的管理员时允许停用另一个
        User admin2 = user(new Role(1, "ADMIN"));
        ok(opsT, "PUT", "/api/sys/users/" + admin2.id() + "/status", "{\"enabled\":false}");
        ok(opsT, "PUT", "/api/sys/users/" + admin2.id() + "/status", "{\"enabled\":true}");
        ok(opsT, "DELETE", "/api/sys/users/" + admin2.id(), null);
        assertThat(code(admin, "GET", "/api/sys/users/" + admin2.id(), null)).isEqualTo(404);
        assertThat(code(admin, "GET", "/api/auth/me", null)).isZero();
    }

    @Test void usernameUniqueIncludingDeletedAndValidation() throws Exception {
        User u = user();
        String body = "{\"username\":\"" + u.username() + "\",\"password\":\"" + PW + "\"}";
        JsonNode dup = call(admin, "POST", "/api/sys/users", body);
        assertThat(dup.path("code").asInt()).isEqualTo(40001);
        assertThat(dup.path("msg").asText()).startsWith("编码已存在（含已删除）");
        ok(admin, "DELETE", "/api/sys/users/" + u.id(), null);
        assertThat(call(admin, "POST", "/api/sys/users", body).path("code").asInt()).as("已删除用户名不可复用").isEqualTo(40001);
        assertThat(code(admin, "POST", "/api/sys/users", "{\"username\":\"bad name\",\"password\":\"" + PW + "\"}")).isEqualTo(400);
        assertThat(code(admin, "POST", "/api/sys/users", "{\"username\":\"" + uniq("v") + "\",\"password\":\"" + PW + "\",\"roleIds\":[999999]}")).isEqualTo(404);
        assertThat(code(admin, "GET", "/api/sys/users/999999", null)).isEqualTo(404);
    }

    @Test void roleAndPermissionCrud() throws Exception {
        String pcode = uniq("demo:perm");
        JsonNode p = ok(admin, "POST", "/api/sys/permissions", "{\"code\":\"" + pcode + "\",\"name\":\"演示\"}");
        long pid = p.path("id").asLong();
        assertThat(call(admin, "POST", "/api/sys/permissions", "{\"code\":\"" + pcode + "\",\"name\":\"x\"}").path("code").asInt()).isEqualTo(40001);
        assertThat(ok(admin, "PUT", "/api/sys/permissions/" + pid, "{\"code\":\"" + pcode + "\",\"name\":\"改名\"}").path("name").asText()).isEqualTo("改名");
        assertThat(code(admin, "PUT", "/api/sys/permissions/" + pid, "{\"code\":\"other\",\"name\":\"x\"}")).isEqualTo(400);

        Role r = role("CRUD");
        JsonNode rv = ok(admin, "PUT", "/api/sys/roles/" + r.id() + "/permissions", "{\"ids\":[" + pid + "]}");
        assertThat(rv.path("permissionIds").get(0).asLong()).isEqualTo(pid);
        assertThat(code(admin, "PUT", "/api/sys/roles/" + r.id() + "/permissions", "{\"ids\":[999999]}")).isEqualTo(404);
        assertThat(call(admin, "POST", "/api/sys/roles", "{\"code\":\"" + r.code() + "\",\"name\":\"x\"}").path("code").asInt()).isEqualTo(40001);
        assertThat(ok(admin, "PUT", "/api/sys/roles/" + r.id(), "{\"code\":\"" + r.code() + "\",\"name\":\"新\"}").path("name").asText()).isEqualTo("新");
        assertThat(code(admin, "PUT", "/api/sys/roles/" + r.id(), "{\"code\":\"ZZ\",\"name\":\"x\"}")).isEqualTo(400);
        assertThat(code(admin, "DELETE", "/api/sys/roles/1", null)).as("内置 ADMIN").isEqualTo(400);

        User holder = user(r);
        String ht = holder.token(this);
        ok(admin, "DELETE", "/api/sys/permissions/" + pid, null);
        assertThat(code(ht, "GET", "/api/auth/me", null)).as("权限点删除后持有者需重新登录").isEqualTo(401);
        assertThat(ok(admin, "GET", "/api/sys/roles/" + r.id(), null).path("permissionIds").size()).isZero();
        ok(admin, "DELETE", "/api/sys/roles/" + r.id(), null);
        assertThat(code(admin, "GET", "/api/sys/roles/" + r.id(), null)).isEqualTo(404);
        assertThat(ok(admin, "GET", "/api/sys/users/" + holder.id(), null).path("roleCodes").size()).isZero();
        assertThat(code(admin, "DELETE", "/api/sys/permissions/999999", null)).isEqualTo(404);
        assertThat(ok(admin, "GET", "/api/sys/roles", null).size()).isPositive();
    }
}
