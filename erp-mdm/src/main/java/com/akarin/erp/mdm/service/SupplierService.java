package com.akarin.erp.mdm.service;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.mdm.entity.Supplier;
import com.akarin.erp.mdm.mapper.SupplierMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class SupplierService extends ServiceImpl<SupplierMapper, Supplier> {
    public Supplier create(Supplier e) {
        if (exists(new LambdaQueryWrapper<Supplier>().eq(Supplier::getCode, e.getCode())))
            throw new BizException(ErrorCode.CODE_DUPLICATED, ErrorCode.CODE_DUPLICATED.getDefaultMsg() + ": " + e.getCode());
        if (e.getStatus() == null) e.setStatus(1);
        save(e);
        return e;
    }

    public Supplier require(Long id) {
        Supplier e = getById(id);
        if (e == null) throw new BizException("Supplier 不存在: " + id);
        return e;
    }
}
