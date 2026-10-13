package com.akarin.erp.mdm.dto;

import com.akarin.erp.mdm.entity.Material;
import com.akarin.erp.common.validation.OnCreate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建/更新请求。code：创建必填；更新时可不传（不改），传了必须与原编码一致，否则 400（编码不可修改）。status：1 启用 / 0 停用。version：更新必填（乐观锁），创建时忽略 */
public record MaterialReq(@NotBlank(groups = OnCreate.class) @Size(max = 64) String code, @NotBlank @Size(max = 128) String name, @Size(max = 128) String spec, @Size(max = 16) String unit, @Size(max = 64) String category,
                     @Min(0) @Max(1) Integer status,
                     Integer version) {
    public Material toEntity() {
        Material e = new Material();
        e.setCode(code);
        e.setName(name);
            e.setSpec(spec);
            e.setUnit(unit);
            e.setCategory(category);
        e.setStatus(status);
        e.setVersion(version);
        return e;
    }
}
