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
        e.setVersion(0);
        try {
            save(e);
        } catch (org.springframework.dao.DuplicateKeyException race) {   // 并发同编码创建
            throw new BizException(ErrorCode.CODE_DUPLICATED, "编码已存在: " + e.getCode());
        }
        return e;
    }

    @Transactional
    public E update(Long id, E patch) {
        E existing = require(id);   // 先查：不存在 404，再校验 version（与用户、角色、权限点一致）
        com.akarin.erp.common.util.Versions.requirePresent(patch.getVersion());
        if (patch.getCode() != null && !patch.getCode().equals(existing.getCode()))
            throw new BizException("编码不可修改: " + existing.getCode());
        patch.setId(id);
        patch.setCode(existing.getCode());
        com.akarin.erp.common.util.Versions.requireUpdated(updateById(patch));
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
        com.akarin.erp.common.util.Versions.requireOneRow(baseMapper.deleteById(id));   // 按 id 软删，不带 version 条件
    }

    public E require(Long id) {
        E e = getById(id);
        if (e == null) throw new BizException(ErrorCode.NOT_FOUND, type().label() + "不存在: " + id);
        return e;
    }

    /** 请求体里引用的主数据：不存在返回 400（路径里的资源不存在才是 404） */
    public E requireRef(Long id) {
        E e = id == null ? null : getById(id);
        if (e == null) throw new BizException(ErrorCode.BAD_REQUEST, type().label() + "不存在: " + id);
        return e;
    }
}
