package com.akarin.erp.common.util;

import com.akarin.erp.common.exception.BizException;
import com.akarin.erp.common.exception.ErrorCode;

/** 乐观锁更新的公共检查：更新必须带 version；按 version 更新影响 0 行说明已被别人改过 → 409/40900 */
public final class Versions {
    private Versions() {}

    public static void requirePresent(Integer version) {
        if (version == null) throw new BizException(ErrorCode.BAD_REQUEST, "version 不能为空（请先查询详情拿到最新 version）");
    }

    /** 定向更新 / 软删：影响行数必须正好是 1，否则说明记录已被删除或并发变化 → 409/40900，不能静默当成功 */
    public static void requireOneRow(int rows) {
        if (rows != 1) throw new BizException(ErrorCode.CONCURRENT_MODIFICATION);
    }

    public static void requireUpdated(boolean updated) {
        if (!updated) throw new BizException(ErrorCode.CONCURRENT_MODIFICATION);
    }
}
