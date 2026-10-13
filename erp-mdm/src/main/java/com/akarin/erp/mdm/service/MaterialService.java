package com.akarin.erp.mdm.service;

import com.akarin.erp.mdm.entity.Material;
import com.akarin.erp.mdm.mapper.MaterialMapper;
import com.akarin.erp.mdm.spi.MasterDataType;
import org.springframework.stereotype.Service;

@Service
public class MaterialService extends MasterDataService<MaterialMapper, Material> {
    @Override
    protected MasterDataType type() { return MasterDataType.MATERIAL; }
}
