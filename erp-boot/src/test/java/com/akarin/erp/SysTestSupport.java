package com.akarin.erp;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** 通过管理接口造用户/角色 */
abstract class SysTestSupport extends ApiTestSupport {
    static final String PW = "pw123456";
    String admin;
    Map<String, Long> permIds = new HashMap<>();

    @BeforeEach void setUpAdmin() throws Exception {
        admin = loginAdmin();
        for (JsonNode p : ok(admin, "GET", "/api/sys/permissions", null)) permIds.put(p.path("code").asText(), p.path("id").asLong());
    }

    record Role(long id, String code) {}
    record User(long id, String username) {
        String token(SysTestSupport s) throws Exception { return s.login(username, PW); }
    }

    Role role(String prefix, String... perms) throws Exception {
        String code = uniq(prefix).toUpperCase();
        long id = ok(admin, "POST", "/api/sys/roles", "{\"code\":\"" + code + "\",\"name\":\"" + code + "\"}").path("id").asLong();
        if (perms.length > 0) {
            String ids = Arrays.stream(perms).map(p -> String.valueOf(permIds.get(p))).collect(Collectors.joining(","));
            ok(admin, "PUT", "/api/sys/roles/" + id + "/permissions", "{\"ids\":[" + ids + "]}");
        }
        return new Role(id, code);
    }

    User user(Role... roles) throws Exception {
        String name = uniq("u");
        String ids = Arrays.stream(roles).map(r -> String.valueOf(r.id())).collect(Collectors.joining(","));
        long id = ok(admin, "POST", "/api/sys/users", "{\"username\":\"" + name + "\",\"password\":\"" + PW +
                "\",\"realName\":\"测试\",\"roleIds\":[" + ids + "]}").path("id").asLong();
        return new User(id, name);
    }

    /** 查询当前 version（乐观锁），用于构造更新请求 */
    long ver(String token, String url) throws Exception {
        var m = java.util.regex.Pattern.compile("^/api/sys/permissions/(\\d+)$").matcher(url);
        if (m.find()) {
            for (JsonNode p : ok(token, "GET", "/api/sys/permissions", null))
                if (p.path("id").asLong() == Long.parseLong(m.group(1))) return p.path("version").asLong();
            throw new AssertionError("permission not found: " + url);
        }
        return ok(token, "GET", url, null).path("version").asLong();
    }
}
