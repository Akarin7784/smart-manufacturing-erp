package com.akarin.erp.mdm.service;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.mdm.entity.Customer;
import com.akarin.erp.mdm.mapper.CustomerMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class CustomerService extends ServiceImpl<CustomerMapper, Customer> {
    public Customer create(Customer e) {
        if (exists(new LambdaQueryWrapper<Customer>().eq(Customer::getCode, e.getCode())))
            throw new BizException(ErrorCode.CODE_DUPLICATED, ErrorCode.CODE_DUPLICATED.getDefaultMsg() + ": " + e.getCode());
        if (e.getStatus() == null) e.setStatus(1);
        save(e);
        return e;
    }

    public Customer require(Long id) {
        Customer e = getById(id);
        if (e == null) throw new BizException("Customer 不存在: " + id);
        return e;
    }
}
