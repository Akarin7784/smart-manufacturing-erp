package com.akarin.erp.inventory.service;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.inventory.entity.Stock;
import com.akarin.erp.inventory.entity.StockTxn;
import com.akarin.erp.inventory.mapper.StockMapper;
import com.akarin.erp.inventory.mapper.StockTxnMapper;
import com.akarin.erp.mdm.service.MaterialService;
import com.akarin.erp.mdm.service.WarehouseService;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class InventoryServiceTest {
    static final Class<?>[] ENTITIES = {Stock.class, StockTxn.class};
    @BeforeAll
    static void initMybatisLambdaCache() {
        var assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        for (Class<?> c : ENTITIES) TableInfoHelper.initTableInfo(assistant, c);
    }

    StockMapper stockMapper = mock(StockMapper.class);
    StockTxnMapper txnMapper = mock(StockTxnMapper.class);
    InventoryService svc = new InventoryService(stockMapper, txnMapper, mock(MaterialService.class), mock(WarehouseService.class));

    static Stock stock(String qty) {
        Stock s = new Stock(); s.setId(1L); s.setMaterialId(1L); s.setWarehouseId(1L); s.setQty(new BigDecimal(qty)); s.setVersion(0);
        return s;
    }

    @Test void outOfStockRejected() {
        when(stockMapper.selectOne(any())).thenReturn(stock("5"));
        assertThatThrownBy(() -> svc.stockOut(1L, 1L, new BigDecimal("6"), "SALES_SHIPMENT", "SO1", null))
                .isInstanceOf(BizException.class).hasMessageContaining("库存不足");
        verify(txnMapper, never()).insert(any(StockTxn.class));
    }

    @Test void outWithoutStockRecordRejected() {
        assertThatThrownBy(() -> svc.stockOut(1L, 1L, BigDecimal.ONE, null, null, null)).hasMessageContaining("库存不足");
    }

    @Test void nonPositiveQtyRejected() {
        assertThatThrownBy(() -> svc.stockIn(1L, 1L, BigDecimal.ZERO, null, null, null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> svc.stockIn(1L, 1L, null, null, null, null)).isInstanceOf(BizException.class);
    }

    @Test void stockInCreatesRecordAndTxn() {
        when(stockMapper.updateById(any(Stock.class))).thenReturn(1);
        StockTxn t = svc.stockIn(1L, 1L, new BigDecimal("3"), null, null, null);
        assertThat(t.getBalanceAfter()).isEqualByComparingTo("3");
        assertThat(t.getBizType()).isEqualTo("MANUAL");
        verify(stockMapper).insert(any(Stock.class));
    }

    @Test void retriesOnVersionConflictThenFails() {
        when(stockMapper.selectOne(any())).thenAnswer(inv -> stock("10"));
        when(stockMapper.updateById(any(Stock.class))).thenReturn(0);
        assertThatThrownBy(() -> svc.stockOut(1L, 1L, BigDecimal.ONE, null, null, null)).hasMessageContaining("并发冲突");
        verify(stockMapper, times(3)).updateById(any(Stock.class));
    }

    @Test void retrySucceedsAfterOneConflict() {
        when(stockMapper.selectOne(any())).thenAnswer(inv -> stock("10"));
        when(stockMapper.updateById(any(Stock.class))).thenReturn(0, 1);
        assertThat(svc.stockOut(1L, 1L, new BigDecimal("4"), "X", "N", null).getBalanceAfter()).isEqualByComparingTo("6");
    }
}
