package com.akarin.erp.auth.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "erp.jwt")
public class JwtProperties {
    /** HS256 密钥，至少 32 字节；生产环境通过环境变量 ERP_JWT_SECRET 注入 */
    private String secret;
    private long expireMinutes = 120;
}
