package com.akarin.erp.mdm.dto;

import com.akarin.erp.mdm.entity.Supplier;
import com.akarin.erp.common.validation.OnCreate;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建/更新请求。code：创建必填；更新时可不传（不改），传了必须与原编码一致，否则 400（编码不可修改）。status：1 启用 / 0 停用。version：更新必填（乐观锁），创建时忽略 */
public record SupplierReq(@NotBlank(groups = OnCreate.class) @Size(max = 64) String code, @NotBlank @Size(max = 128) String name, @Size(max = 64) String contact, @Size(max = 32) String phone,
                     @Min(0) @Max(1) Integer status,
                     Integer version) {
    public Supplier toEntity() {
        Supplier e = new Supplier();
        e.setCode(code);
        e.setName(name);
            e.setContact(contact);
            e.setPhone(phone);
        e.setStatus(status);
        e.setVersion(version);
        return e;
    }
}
