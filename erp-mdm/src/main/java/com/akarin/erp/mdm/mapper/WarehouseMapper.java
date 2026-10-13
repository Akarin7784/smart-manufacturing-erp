package com.akarin.erp.mdm.mapper;

import com.akarin.erp.mdm.entity.Warehouse;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface WarehouseMapper extends BaseMapper<Warehouse>, CodeCountingMapper {
    @Override
    @Select("SELECT COUNT(*) FROM mdm_warehouse WHERE code = #{code}")
    long countByCodeIncludingDeleted(String code);
}
