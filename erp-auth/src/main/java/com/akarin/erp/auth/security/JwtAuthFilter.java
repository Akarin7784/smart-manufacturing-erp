package com.akarin.erp.auth.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {
    private final JwtTokenProvider tokens;
    private final UserTokenState tokenState;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            try {
                var claims = tokens.parse(h.substring(7));
                Integer tv = claims.get(JwtTokenProvider.CLAIM_TOKEN_VERSION, Integer.class);
                if (tokenState.isValid(claims.getSubject(), tv)) {
                    @SuppressWarnings("unchecked")
                    List<String> auth = claims.get(JwtTokenProvider.CLAIM_AUTH, List.class);
                    var authorities = auth.stream().map(SimpleGrantedAuthority::new).toList();
                    SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(claims.getSubject(), null, authorities));
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // token 无效则保持匿名，由 Security 返回 401
            }
        }
        chain.doFilter(req, res);
    }
}
