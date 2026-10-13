package com.akarin.erp.workflow.mapper;

import com.akarin.erp.workflow.entity.ProcessDef;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ProcessDefMapper extends BaseMapper<ProcessDef> {
    /** 物理删除：biz_type 唯一，删除后需要能重新配置 */
    @Delete("DELETE FROM wf_process_def WHERE id = #{id}")
    int hardDelete(Long id);
}
