package com.akarin.erp.mdm.service;

import com.akarin.erp.common.domain.BaseEntity;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.mdm.mapper.CodeCountingMapper;
import com.akarin.erp.mdm.spi.MasterData;
import com.akarin.erp.mdm.spi.MasterDataReferenceChecker;
import com.akarin.erp.mdm.spi.MasterDataType;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 主数据通用规则：编码唯一且不可复用（含已删除）、编码创建后不可修改、删除为逻辑删除、被单据引用时不能删除只能停用。
 */
public abstract class MasterDataService<M extends BaseMapper<E> & CodeCountingMapper, E extends BaseEntity & MasterData>
        extends ServiceImpl<M, E> {
    public static final int ENABLED = 1, DISABLED = 0;
    private List<MasterDataReferenceChecker> checkers = List.of();

    @Autowired(required = false)
    void setCheckers(List<MasterDataReferenceChecker> checkers) { this.checkers = checkers; }

    protected abstract MasterDataType type();

    @Transactional
    public E create(E e) {
        if (baseMapper.countByCodeIncludingDeleted(e.getCode()) > 0)
            throw new BizException(ErrorCode.CODE_DUPLICATED, "编码已存在（含已删除）: " + e.getCode());
        e.setId(null);
        if (e.getStatus() == null) e.setStatus(ENABLED);
        save(e);
        return e;
    }

    @Transactional
    public E update(Long id, E patch) {
        E existing = require(id);
        if (patch.getCode() != null && !patch.getCode().equals(existing.getCode()))
            throw new BizException("编码不可修改: " + existing.getCode());
        patch.setId(id);
        patch.setCode(existing.getCode());
        updateById(patch);
        return require(id);
    }

    @Transactional
    public void delete(Long id) {
        require(id);
        for (MasterDataReferenceChecker c : checkers) {
            Optional<String> ref = c.findReference(type(), id);
            if (ref.isPresent())
                throw new BizException(ErrorCode.MASTER_DATA_REFERENCED, "已被" + ref.get() + "引用，不能删除，只能停用");
        }
        removeById(id);
    }

    public E require(Long id) {
        E e = getById(id);
        if (e == null) throw new BizException(ErrorCode.NOT_FOUND, type() + " 不存在: " + id);
        return e;
    }
}
