package com.akarin.erp.mdm.mapper;

public interface CodeCountingMapper {
    /** 统计编码（含已逻辑删除的记录），用于"编码不可复用" */
    long countByCodeIncludingDeleted(String code);
}
