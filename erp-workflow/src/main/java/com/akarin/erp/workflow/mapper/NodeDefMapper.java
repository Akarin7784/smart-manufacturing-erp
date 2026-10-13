package com.akarin.erp.workflow.mapper;

import com.akarin.erp.workflow.entity.NodeDef;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface NodeDefMapper extends BaseMapper<NodeDef> {
    /** 历史实例查看节点快照：流程重新配置后旧节点为逻辑删除，仍可查询 */
    @Select("SELECT * FROM wf_node_def WHERE id = #{id}")
    NodeDef selectByIdIncludingDeleted(Long id);
}
