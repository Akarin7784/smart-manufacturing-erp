package com.akarin.erp.finance.mapper;

import com.akarin.erp.finance.dto.ReconciliationRow;
import com.akarin.erp.finance.entity.ArapBill;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ArapBillMapper extends BaseMapper<ArapBill> {
    /** 按往来单位汇总：单据数、应收/应付总额、已核销、余额 */
    @Select("<script>SELECT bill_type AS billType, partner_id AS partnerId, COUNT(*) AS billCount, " +
            "SUM(amount) AS totalAmount, SUM(settled_amount) AS settledAmount, " +
            "SUM(amount) - SUM(settled_amount) AS balance FROM fin_arap_bill WHERE deleted = 0 " +
            "<if test='billType != null'> AND bill_type = #{billType}</if>" +
            "<if test='partnerId != null'> AND partner_id = #{partnerId}</if>" +
            " GROUP BY bill_type, partner_id ORDER BY bill_type, partner_id</script>")
    List<ReconciliationRow> reconcile(@Param("billType") String billType, @Param("partnerId") Long partnerId);
}
