package com.akarin.erp.mdm.spi;

/** 主数据公共属性 */
public interface MasterData {
    Long getId();
    void setId(Long id);
    String getCode();
    void setCode(String code);
    Integer getStatus();
    void setStatus(Integer status);
    Integer getVersion();
    void setVersion(Integer version);
}
