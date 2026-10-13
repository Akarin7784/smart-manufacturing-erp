package com.akarin.erp.auth.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

class JwtTokenProviderTest {
    static JwtTokenProvider provider(String secret, long minutes) {
        JwtProperties p = new JwtProperties();
        p.setSecret(secret); p.setExpireMinutes(minutes);
        return new JwtTokenProvider(p);
    }
    static final String SECRET = "unit-test-secret-unit-test-secret-0123456789";

    @Test void roundTripCarriesSubjectAuthoritiesAndTokenVersion() {
        var jwt = provider(SECRET, 10);
        var claims = jwt.parse(jwt.create("alice", List.of("mdm:write", "ROLE_X"), 7));
        assertThat(claims.getSubject()).isEqualTo("alice");
        assertThat(claims.get(JwtTokenProvider.CLAIM_AUTH, List.class)).containsExactly("mdm:write", "ROLE_X");
        assertThat(claims.get(JwtTokenProvider.CLAIM_TOKEN_VERSION, Integer.class)).isEqualTo(7);
    }

    @Test void tamperedTokenRejected() {
        var jwt = provider(SECRET, 10);
        String t = jwt.create("alice", List.of(), 0);
        String tampered = t.substring(0, t.length() - 2) + (t.endsWith("aa") ? "bb" : "aa");
        assertThatThrownBy(() -> jwt.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test void tokenSignedWithOtherKeyRejected() {
        String t = provider("another-secret-another-secret-0123456789xx", 10).create("alice", List.of(), 0);
        assertThatThrownBy(() -> provider(SECRET, 10).parse(t)).isInstanceOf(JwtException.class);
    }

    @Test void expiredTokenRejected() {
        var jwt = provider(SECRET, -1);
        String t = jwt.create("alice", List.of(), 0);
        assertThatThrownBy(() -> jwt.parse(t)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test void garbageRejected() {
        assertThatThrownBy(() -> provider(SECRET, 10).parse("not.a.jwt")).isInstanceOf(JwtException.class);
    }
}
