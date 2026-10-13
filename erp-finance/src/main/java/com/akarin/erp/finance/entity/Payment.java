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

/** 收付款登记：RECEIPT 收款（客户） / PAYMENT 付款（供应商） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fin_payment")
public class Payment extends BaseEntity {
    public static final String RECEIPT = "RECEIPT", PAYMENT = "PAYMENT";

    private String paymentNo;
    private String paymentType;
    private Long partnerId;
    private BigDecimal amount;
    private BigDecimal appliedAmount;
    private LocalDate paidDate;
    private String remark;
    @Version
    private Integer version;

    public BigDecimal unapplied() {
        return Money.of(amount).subtract(Money.of(appliedAmount));
    }

    public void apply(BigDecimal amt) {
        BigDecimal a = Money.of(amt);
        if (a.compareTo(unapplied()) > 0)
            throw new BizException("超额核销：收付款未核销余额 " + unapplied() + "，本次 " + a);
        appliedAmount = Money.of(appliedAmount).add(a);
    }

    /** 付款对应应付，收款对应应收 */
    public static String billTypeOf(String paymentType) {
        if (RECEIPT.equals(paymentType)) return ArapBill.AR;
        if (PAYMENT.equals(paymentType)) return ArapBill.AP;
        throw new BizException("未知收付款类型: " + paymentType);
    }
}
