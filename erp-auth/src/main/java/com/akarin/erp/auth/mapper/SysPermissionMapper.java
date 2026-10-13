package com.akarin.erp.auth.mapper;

import com.akarin.erp.auth.entity.SysPermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {
    @Select("SELECT COUNT(*) FROM sys_permission WHERE code = #{code}")
    long countByCodeIncludingDeleted(String code);
}
