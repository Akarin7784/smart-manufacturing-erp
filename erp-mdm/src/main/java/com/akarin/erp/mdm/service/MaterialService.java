package com.akarin.erp.mdm.service;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.mdm.entity.Material;
import com.akarin.erp.mdm.mapper.MaterialMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

@Service
public class MaterialService extends ServiceImpl<MaterialMapper, Material> {
    public Material create(Material e) {
        if (exists(new LambdaQueryWrapper<Material>().eq(Material::getCode, e.getCode())))
            throw new BizException("编码已存在: " + e.getCode());
        if (e.getStatus() == null) e.setStatus(1);
        save(e);
        return e;
    }

    public Material require(Long id) {
        Material e = getById(id);
        if (e == null) throw new BizException("Material 不存在: " + id);
        return e;
    }
}
