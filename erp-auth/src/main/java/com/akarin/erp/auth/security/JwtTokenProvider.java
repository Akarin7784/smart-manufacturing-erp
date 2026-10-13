package com.akarin.erp.auth.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;

@Component
@RequiredArgsConstructor
public class JwtTokenProvider {
    private final JwtProperties props;

    private SecretKey key() { return Keys.hmacShaKeyFor(props.getSecret().getBytes(StandardCharsets.UTF_8)); }

    public String create(String username, List<String> authorities) {
        long now = System.currentTimeMillis();
        return Jwts.builder().subject(username).claim("auth", authorities)
                .issuedAt(new Date(now)).expiration(new Date(now + props.getExpireMinutes() * 60_000))
                .signWith(key()).compact();
    }

    public Claims parse(String token) {
        return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();
    }
}
