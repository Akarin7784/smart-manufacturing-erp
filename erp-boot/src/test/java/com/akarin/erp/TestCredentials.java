package com.akarin.erp;

/**
 * 测试统一使用的 admin 密码：读 ERP_ADMIN_PASSWORD，未设置时为 admin123，
 * 与 application-h2.yml 的 erp.init.admin-password 默认值（${ERP_ADMIN_PASSWORD:admin123}）一致。
 * MySQL profile 没有默认值，CI 通过环境变量提供。
 */
final class TestCredentials {
    static final String ADMIN_PASSWORD = System.getenv().getOrDefault("ERP_ADMIN_PASSWORD", "admin123");
    private TestCredentials() {}
}
