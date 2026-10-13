package com.akarin.erp.mdm.dto;

import com.akarin.erp.mdm.entity.Supplier;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 创建/更新请求；更新时 code 必须与原编码一致（编码不可修改）；status：1 启用 / 0 停用 */
public record SupplierReq(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 128) String name, @Size(max = 64) String contact, @Size(max = 32) String phone,
                     @Min(0) @Max(1) Integer status) {
    public Supplier toEntity() {
        Supplier e = new Supplier();
        e.setCode(code);
        e.setName(name);
            e.setContact(contact);
            e.setPhone(phone);
        e.setStatus(status);
        return e;
    }
}
