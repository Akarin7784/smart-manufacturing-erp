package com.akarin.erp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/** 集成测试公共方法：登录、调用、断言 */
abstract class ApiTestSupport {
    static final AtomicInteger SEQ = new AtomicInteger();
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;

    /** admin 登录，密码见 {@link TestCredentials#ADMIN_PASSWORD} */
    String loginAdmin() throws Exception { return login("admin", TestCredentials.ADMIN_PASSWORD); }

    String login(String username, String password) throws Exception {
        return call(null, "POST", "/api/auth/login",
                "{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}").path("data").path("token").asText();
    }

    JsonNode call(String token, String method, String url, String body) throws Exception {
        var req = request(HttpMethod.valueOf(method), url).contentType(MediaType.APPLICATION_JSON);
        if (body != null) req.content(body);
        if (token != null) req.header("Authorization", "Bearer " + token);
        var res = mvc.perform(req).andReturn().getResponse();
        String s = res.getContentAsString(StandardCharsets.UTF_8);
        if (s.isEmpty()) return om.createObjectNode().put("http", res.getStatus());
        JsonNode resp = om.readTree(s);
        int code = resp.path("code").asInt();
        // HTTP 状态必须与 body.code 一致
        assertThat(res.getStatus()).as(method + " " + url + " http status vs body " + resp).isEqualTo(code == 0 ? 200 : code >= 10000 ? code / 100 : code);
        return resp;
    }

    JsonNode ok(String token, String method, String url, String body) throws Exception {
        JsonNode r = call(token, method, url, body);
        assertThat(r.path("code").asInt()).as(method + " " + url + " -> " + r).isZero();
        return r.path("data");
    }

    int code(String token, String method, String url, String body) throws Exception {
        JsonNode r = call(token, method, url, body);
        return r.has("http") ? r.path("http").asInt() : r.path("code").asInt();
    }

    static String uniq(String prefix) {
        return prefix + SEQ.incrementAndGet() + UUID.randomUUID().toString().substring(0, 6);
    }

    long mdm(String token, String path, String prefix) throws Exception {
        String c = uniq(prefix);
        return ok(token, "POST", "/api/mdm/" + path, "{\"code\":\"" + c + "\",\"name\":\"" + c + "\"}").path("id").asLong();
    }
}
