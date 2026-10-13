package com.akarin.erp.auth.mapper;

import com.akarin.erp.auth.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import java.util.List;
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    /** 用户 -> 角色 -> 权限点 */
    @Select("SELECT DISTINCT p.code FROM sys_permission p JOIN sys_role_permission rp ON rp.permission_id = p.id " +
            "JOIN sys_user_role ur ON ur.role_id = rp.role_id WHERE ur.user_id = #{userId} AND p.deleted = 0")
    List<String> selectPermissionCodes(Long userId);

    @Select("SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id WHERE ur.user_id = #{userId} AND r.deleted = 0")
    List<String> selectRoleCodes(Long userId);
}
