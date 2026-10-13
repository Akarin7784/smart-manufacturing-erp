package com.akarin.erp.mdm.mapper;

import com.akarin.erp.mdm.entity.Material;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MaterialMapper extends BaseMapper<Material>, CodeCountingMapper {
    @Override
    @Select("SELECT COUNT(*) FROM mdm_material WHERE code = #{code}")
    long countByCodeIncludingDeleted(String code);
}
