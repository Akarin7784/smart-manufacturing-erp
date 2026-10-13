package com.akarin.erp.finance.entity;

import com.akarin.erp.common.domain.BaseEntity;
import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.util.Money;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/** 应收/应付单 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fin_arap_bill")
public class ArapBill extends BaseEntity {
    public static final String AR = "AR", AP = "AP";
    public static final String OPEN = "OPEN", PARTIAL = "PARTIAL", SETTLED = "SETTLED";

    private String billNo;
    /** AR 应收（客户） / AP 应付（供应商） */
    private String billType;
    private Long partnerId;
    private String sourceBizType;
    private String sourceBizNo;
    private BigDecimal amount;
    private BigDecimal settledAmount;
    /** OPEN 未核销 → PARTIAL 部分核销 → SETTLED 已核销 */
    private String status;
    private LocalDate dueDate;
    @Version
    private Integer version;

    /** 未核销余额 */
    public BigDecimal remaining() {
        return Money.of(amount).subtract(Money.of(settledAmount));
    }

    /** 核销：校验金额、推进状态机 */
    public void settle(BigDecimal amt) {
        BigDecimal a = Money.requireAmount(amt, "核销金额");
        if (SETTLED.equals(status)) throw new BizException("单据已核销完毕: " + billNo);
        if (a.compareTo(remaining()) > 0)
            throw new BizException("超额核销：余额 " + remaining() + "，本次 " + a);
        settledAmount = Money.of(settledAmount).add(a);
        status = settledAmount.compareTo(Money.of(amount)) == 0 ? SETTLED : PARTIAL;
    }
}
