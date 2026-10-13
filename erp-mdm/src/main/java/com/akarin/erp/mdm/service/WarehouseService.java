package com.akarin.erp.mdm.service;

import com.akarin.erp.mdm.entity.Warehouse;
import com.akarin.erp.mdm.mapper.WarehouseMapper;
import com.akarin.erp.mdm.spi.MasterDataType;
import org.springframework.stereotype.Service;

@Service
public class WarehouseService extends MasterDataService<WarehouseMapper, Warehouse> {
    @Override
    protected MasterDataType type() { return MasterDataType.WAREHOUSE; }
}
