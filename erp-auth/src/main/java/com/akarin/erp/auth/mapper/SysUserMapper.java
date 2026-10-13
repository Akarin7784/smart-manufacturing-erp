package com.akarin.erp.auth.mapper;

import com.akarin.erp.auth.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
    /** 用户 -> 角色 -> 权限点 */
    @Select("SELECT DISTINCT p.code FROM sys_permission p JOIN sys_role_permission rp ON rp.permission_id = p.id " +
            "JOIN sys_user_role ur ON ur.role_id = rp.role_id JOIN sys_role r ON r.id = ur.role_id " +
            "WHERE ur.user_id = #{userId} AND p.deleted = 0 AND r.deleted = 0")
    List<String> selectPermissionCodes(Long userId);

    @Select("SELECT r.code FROM sys_role r JOIN sys_user_role ur ON ur.role_id = r.id WHERE ur.user_id = #{userId} AND r.deleted = 0")
    List<String> selectRoleCodes(Long userId);

    /** 启用状态的 ADMIN 用户数，用于"不能删除/停用最后一个管理员" */
    @Select("SELECT COUNT(DISTINCT u.id) FROM sys_user u JOIN sys_user_role ur ON ur.user_id = u.id " +
            "JOIN sys_role r ON r.id = ur.role_id WHERE r.code = 'ADMIN' AND r.deleted = 0 AND u.deleted = 0 AND u.status = 1")
    long countEnabledAdmins();

    @Select("SELECT u.username FROM sys_user u JOIN sys_user_role ur ON ur.user_id = u.id JOIN sys_role r ON r.id = ur.role_id " +
            "WHERE r.code = #{roleCode} AND r.deleted = 0 AND u.deleted = 0 AND u.status = 1 ORDER BY u.username")
    List<String> selectUsernamesWithRole(String roleCode);

    /** 含已逻辑删除的记录：用户名不可复用 */
    @Select("SELECT COUNT(*) FROM sys_user WHERE username = #{username}")
    long countByUsernameIncludingDeleted(String username);

    @Update("UPDATE sys_user SET token_version = token_version + 1 WHERE id = #{userId}")
    int bumpTokenVersion(Long userId);

    @Update("UPDATE sys_user SET token_version = token_version + 1 WHERE id IN (SELECT user_id FROM sys_user_role WHERE role_id = #{roleId})")
    int bumpTokenVersionByRole(Long roleId);
}
