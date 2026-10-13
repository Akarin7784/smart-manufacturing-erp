package com.akarin.erp.auth.mapper;

import com.akarin.erp.auth.entity.SysRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {
    @Select("SELECT COUNT(*) FROM sys_role WHERE code = #{code}")
    long countByCodeIncludingDeleted(String code);
}
