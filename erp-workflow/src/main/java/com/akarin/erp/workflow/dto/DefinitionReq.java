package com.akarin.erp.workflow.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;

public record DefinitionReq(@NotBlank @Size(max = 64) String name, Boolean enabled,
                            @NotEmpty @Valid List<Node> nodes) {
    public record Node(@NotBlank @Size(max = 64) String name,
                       @NotBlank @Size(max = 64) String roleCode,
                       @NotBlank @Pattern(regexp = "ALL|ANY", message = "只能是 ALL（会签）或 ANY（或签）") String approveMode,
                       @DecimalMin("0") BigDecimal minAmount) {}
}
