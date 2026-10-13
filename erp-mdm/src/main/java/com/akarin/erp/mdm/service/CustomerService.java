package com.akarin.erp.mdm.service;

import com.akarin.erp.mdm.entity.Customer;
import com.akarin.erp.mdm.mapper.CustomerMapper;
import com.akarin.erp.mdm.spi.MasterDataType;
import org.springframework.stereotype.Service;

@Service
public class CustomerService extends MasterDataService<CustomerMapper, Customer> {
    @Override
    protected MasterDataType type() { return MasterDataType.CUSTOMER; }
}
