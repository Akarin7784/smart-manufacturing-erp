package com.akarin.erp.mdm.service;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.mdm.entity.Warehouse;
import com.akarin.erp.mdm.mapper.WarehouseMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class WarehouseService extends ServiceImpl<WarehouseMapper, Warehouse> {
    public Warehouse create(Warehouse e) {
        if (exists(new LambdaQueryWrapper<Warehouse>().eq(Warehouse::getCode, e.getCode())))
            throw new BizException("编码已存在: " + e.getCode());
        if (e.getStatus() == null) e.setStatus(1);
        save(e);
        return e;
    }

    public Warehouse require(Long id) {
        Warehouse e = getById(id);
        if (e == null) throw new BizException("Warehouse 不存在: " + id);
        return e;
    }
}
