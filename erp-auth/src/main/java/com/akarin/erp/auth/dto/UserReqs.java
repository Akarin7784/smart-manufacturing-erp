package com.akarin.erp.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

public final class UserReqs {
    private UserReqs() {}

    public record Create(@NotBlank @Size(max = 64) @Pattern(regexp = "^[A-Za-z0-9_.-]+$", message = "只能包含字母、数字、_ . -") String username,
                         @NotBlank @Size(min = 6, max = 64) String password,
                         @Size(max = 64) String realName,
                         List<Long> roleIds) {}

    public record Update(@Size(max = 64) String realName) {}

    public record Password(@NotBlank @Size(min = 6, max = 64) String password) {}

    public record Status(@NotNull Boolean enabled) {}

    public record Ids(@NotNull List<Long> ids) {}
}
