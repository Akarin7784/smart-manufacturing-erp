package com.akarin.erp.mdm.service;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;
import com.akarin.erp.mdm.entity.Material;
import com.akarin.erp.mdm.mapper.MaterialMapper;
import com.akarin.erp.mdm.spi.MasterDataReferenceChecker;
import com.akarin.erp.mdm.spi.MasterDataType;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class MasterDataServiceTest {
    @BeforeAll
    static void initMybatisLambdaCache() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : new Class<?>[]{Material.class}) TableInfoHelper.initTableInfo(assistant, c);
    }

    MaterialMapper mapper = mock(MaterialMapper.class);
    MaterialService svc = new MaterialService();

    @BeforeEach void setUp() {
        ReflectionTestUtils.setField(svc, "baseMapper", mapper);
        when(mapper.insert(any(Material.class))).thenReturn(1);
        when(mapper.update(any(Material.class), any())).thenReturn(1);
        when(mapper.deleteById(anyLong())).thenReturn(1);
    }

    static Material m(Long id, String code) {
        Material x = new Material(); x.setId(id); x.setCode(code); x.setName("n"); return x;
    }

    @Test void duplicateCodeIncludingDeletedRejected() {
        when(mapper.countByCodeIncludingDeleted("A")).thenReturn(1L);
        assertThatThrownBy(() -> svc.create(m(null, "A"))).isInstanceOf(BizException.class)
                .hasMessageStartingWith("编码已存在（含已删除）").extracting("errorCode").isEqualTo(ErrorCode.CODE_DUPLICATED);
    }

    @Test void concurrentInsertDuplicateKeyMapsTo40001() {
        when(mapper.insert(any(Material.class))).thenThrow(new org.springframework.dao.DuplicateKeyException("uk"));
        assertThatThrownBy(() -> svc.create(m(null, "C"))).isInstanceOf(BizException.class)
                .hasMessageStartingWith("编码已存在").extracting("errorCode").isEqualTo(ErrorCode.CODE_DUPLICATED);
    }

    @Test void createDefaultsToEnabledAndIgnoresClientId() {
        Material x = svc.create(m(99L, "B"));
        assertThat(x.getStatus()).isEqualTo(MasterDataService.ENABLED);
        assertThat(x.getId()).isNull();
    }

    @Test void codeIsImmutable() {
        when(mapper.selectById(1L)).thenReturn(m(1L, "A"));
        Material other = m(null, "B"); other.setVersion(0);
        assertThatThrownBy(() -> svc.update(1L, other)).hasMessageContaining("编码不可修改");
        Material patch = m(null, null); patch.setName("new"); patch.setVersion(0);
        svc.update(1L, patch);
        assertThat(patch.getCode()).as("编码不写回").isNull();
        assertThat(patch.getVersion()).as("version 只在 WHERE 里，SET 用 version + 1").isNull();
        verify(mapper).update(org.mockito.ArgumentMatchers.eq(patch), any());
        verify(mapper, never()).updateById(any(Material.class));
    }

    @Test void updateMissingRecordIs404BeforeVersionCheck() {
        assertThatThrownBy(() -> svc.update(42L, m(null, null))).extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test void softDeleteMustAffectOneRow() {
        when(mapper.selectById(1L)).thenReturn(m(1L, "A"));
        svc.setCheckers(List.of());
        when(mapper.deleteById(1L)).thenReturn(0);
        assertThatThrownBy(() -> svc.delete(1L)).extracting("errorCode").isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test void updateRequiresVersionAndMapsStaleTo40900() {
        when(mapper.selectById(1L)).thenReturn(m(1L, "A"));
        assertThatThrownBy(() -> svc.update(1L, m(null, null))).hasMessageStartingWith("version 不能为空")
                .extracting("errorCode").isEqualTo(ErrorCode.BAD_REQUEST);
        when(mapper.update(any(Material.class), any())).thenReturn(0);
        Material stale = m(null, null); stale.setVersion(3);
        assertThatThrownBy(() -> svc.update(1L, stale)).extracting("errorCode").isEqualTo(ErrorCode.CONCURRENT_MODIFICATION);
    }

    @Test void referencedCannotBeDeleted() {
        when(mapper.selectById(1L)).thenReturn(m(1L, "A"));
        MasterDataReferenceChecker none = (t, id) -> Optional.empty();
        MasterDataReferenceChecker po = (t, id) -> t == MasterDataType.MATERIAL ? Optional.of("采购订单") : Optional.empty();
        svc.setCheckers(List.of(none, po));
        assertThatThrownBy(() -> svc.delete(1L)).extracting("errorCode").isEqualTo(ErrorCode.MASTER_DATA_REFERENCED);
        verify(mapper, never()).deleteById(anyLong());
        svc.setCheckers(List.of(none));
        svc.delete(1L);
        verify(mapper).deleteById(1L);
    }

    @Test void missingIs404() {
        assertThatThrownBy(() -> svc.require(5L)).extracting("errorCode").isEqualTo(ErrorCode.NOT_FOUND);
    }
}
