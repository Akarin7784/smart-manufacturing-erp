package com.akarin.erp.workflow.dto;

import com.akarin.erp.workflow.entity.NodeDef;
import com.akarin.erp.workflow.entity.ProcessDef;
import java.util.List;

public record DefinitionView(ProcessDef process, List<NodeDef> nodes) {}
