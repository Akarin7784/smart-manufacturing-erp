package com.akarin.erp.finance.spi;

import com.akarin.erp.finance.entity.ArapBill;
import com.akarin.erp.finance.entity.Payment;
import com.akarin.erp.finance.mapper.ArapBillMapper;
import com.akarin.erp.finance.mapper.PaymentMapper;
import com.akarin.erp.mdm.spi.MasterDataReferenceChecker;
import com.akarin.erp.mdm.spi.MasterDataType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 应收应付单（AR→客户 / AP→供应商）与收付款（RECEIPT→客户 / PAYMENT→供应商）对往来单位的引用检查 */
@Component
@RequiredArgsConstructor
public class FinanceReferenceChecker implements MasterDataReferenceChecker {
    private final ArapBillMapper billMapper;
    private final PaymentMapper paymentMapper;

    @Override
    public Optional<String> findReference(MasterDataType type, Long id) {
        String billType, paymentType;
        switch (type) {
            case CUSTOMER -> { billType = ArapBill.AR; paymentType = Payment.RECEIPT; }
            case SUPPLIER -> { billType = ArapBill.AP; paymentType = Payment.PAYMENT; }
            default -> { return Optional.empty(); }
        }
        if (billMapper.exists(new LambdaQueryWrapper<ArapBill>()
                .eq(ArapBill::getBillType, billType).eq(ArapBill::getPartnerId, id))) return Optional.of("应收应付单");
        if (paymentMapper.exists(new LambdaQueryWrapper<Payment>()
                .eq(Payment::getPaymentType, paymentType).eq(Payment::getPartnerId, id))) return Optional.of("收付款");
        return Optional.empty();
    }
}
