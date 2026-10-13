package com.akarin.erp.finance.spi;

import com.akarin.erp.finance.entity.ArapBill;
import com.akarin.erp.finance.mapper.ArapBillMapper;
import com.akarin.erp.mdm.spi.MasterDataReferenceChecker;
import com.akarin.erp.mdm.spi.MasterDataType;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** 应收应付单对客户（AR）/ 供应商（AP）的引用检查 */
@Component
@RequiredArgsConstructor
public class FinanceReferenceChecker implements MasterDataReferenceChecker {
    private final ArapBillMapper billMapper;

    @Override
    public Optional<String> findReference(MasterDataType type, Long id) {
        String billType = switch (type) {
            case CUSTOMER -> "AR";
            case SUPPLIER -> "AP";
            default -> null;
        };
        if (billType == null) return Optional.empty();
        boolean used = billMapper.exists(new LambdaQueryWrapper<ArapBill>()
                .eq(ArapBill::getBillType, billType).eq(ArapBill::getPartnerId, id));
        return used ? Optional.of("应收应付单") : Optional.empty();
    }
}
