package com.akarin.erp.mdm.mapper;

import com.akarin.erp.mdm.entity.Supplier;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SupplierMapper extends BaseMapper<Supplier>, CodeCountingMapper {
    @Override
    @Select("SELECT COUNT(*) FROM mdm_supplier WHERE code = #{code}")
    long countByCodeIncludingDeleted(String code);
}
