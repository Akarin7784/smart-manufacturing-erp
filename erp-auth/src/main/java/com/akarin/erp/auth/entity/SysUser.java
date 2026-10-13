package com.akarin.erp.auth.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseEntity {
    public static final int ENABLED = 1, DISABLED = 0;
    private String username;
    @JsonIgnore
    private String password;
    private String realName;
    private Integer status;
    /** 令牌版本：停用、改密、调整角色时 +1，使旧 token 失效 */
    @JsonIgnore
    private Integer tokenVersion;
}
