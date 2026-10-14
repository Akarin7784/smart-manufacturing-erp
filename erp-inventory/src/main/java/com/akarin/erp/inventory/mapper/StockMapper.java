package com.akarin.erp.inventory.mapper;

import com.akarin.erp.inventory.entity.Stock;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface StockMapper extends BaseMapper<Stock> {
    /**
     * 保证 (物料, 仓库) 库存行存在：不存在则插入 qty=0，已存在则什么都不改。
     * 用 ON DUPLICATE KEY UPDATE 而不是“先插、捕获唯一键冲突再重读”：冲突在语句内消化，不抛异常，事务不会被标成 rollback-only。
     * 已被逻辑删除的行（deleted=1，唯一键仍占着）：复活为 deleted=0、qty 从 0 开始、version+1（#18 e）。
     * 删除意味着旧数量作废，不恢复旧 qty；赋值顺序保证 CASE 读到的是旧的 deleted（MySQL 按从左到右求值）。
     * 并发首插时后来者在 MySQL 上等待前者提交后走 UPDATE 分支；H2 以 MODE=MySQL 运行，同样支持该语法。
     */
    @Insert("INSERT INTO inv_stock (material_id, warehouse_id, qty, version, deleted) VALUES (#{materialId}, #{warehouseId}, 0, 0, 0) " +
            "ON DUPLICATE KEY UPDATE " +
            "qty = CASE WHEN deleted = 1 THEN 0 ELSE qty END, " +
            "version = CASE WHEN deleted = 1 THEN version + 1 ELSE version END, " +
            "deleted = 0")
    int ensureRow(@Param("materialId") Long materialId, @Param("warehouseId") Long warehouseId);
}
