package com.akarin.erp.mdm.service;

import com.akarin.erp.mdm.entity.Supplier;
import com.akarin.erp.mdm.mapper.SupplierMapper;
import com.akarin.erp.mdm.spi.MasterDataType;
import org.springframework.stereotype.Service;

@Service
public class SupplierService extends MasterDataService<SupplierMapper, Supplier> {
    @Override
    protected MasterDataType type() { return MasterDataType.SUPPLIER; }
}
