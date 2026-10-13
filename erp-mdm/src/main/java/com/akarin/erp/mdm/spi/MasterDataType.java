package com.akarin.erp.mdm.spi;

public enum MasterDataType {
    MATERIAL("物料"), CUSTOMER("客户"), SUPPLIER("供应商"), WAREHOUSE("仓库");

    private final String label;
    MasterDataType(String label) { this.label = label; }
    public String label() { return label; }
}
