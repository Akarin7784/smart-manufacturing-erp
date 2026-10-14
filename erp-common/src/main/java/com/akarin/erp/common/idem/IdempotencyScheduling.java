package com.akarin.erp.common.idem;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 启用 {@link IdempotencyService#purgeExpired()} 的定时清理 */
@Configuration
@EnableScheduling
class IdempotencyScheduling {}
