package com.akarin.erp.common.spi;

import java.util.List;

/** 审批人目录：按角色查启用用户。由 erp-auth 实现，erp-workflow 只依赖此接口 */
public interface ApproverDirectory {
    List<String> usernamesWithRole(String roleCode);
}
