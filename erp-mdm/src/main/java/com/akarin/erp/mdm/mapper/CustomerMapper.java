package com.akarin.erp.mdm.mapper;

import com.akarin.erp.mdm.entity.Customer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CustomerMapper extends BaseMapper<Customer>, CodeCountingMapper {
    @Override
    @Select("SELECT COUNT(*) FROM mdm_customer WHERE code = #{code}")
    long countByCodeIncludingDeleted(String code);
}
