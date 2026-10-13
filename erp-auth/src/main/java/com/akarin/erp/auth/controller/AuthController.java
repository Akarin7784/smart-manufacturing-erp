package com.akarin.erp.auth.controller;

import com.akarin.erp.auth.service.AuthService;
import com.akarin.erp.common.web.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    public record LoginReq(@NotBlank String username, @NotBlank String password) {}

    @PostMapping("/login")
    public R<Map<String, String>> login(@Valid @RequestBody LoginReq req) {
        return R.ok(Map.of("token", authService.login(req.username(), req.password())));
    }

    @GetMapping("/me")
    public R<Map<String, Object>> me(Authentication auth) {
        return R.ok(Map.of("username", auth.getName(),
                "authorities", auth.getAuthorities().stream().map(Object::toString).toList()));
    }
}
