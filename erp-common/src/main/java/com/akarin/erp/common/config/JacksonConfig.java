package com.akarin.erp.common.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.math.BigDecimal;

/**
 * BigDecimal 一律序列化成字符串（toPlainString，不用科学计数法），避免前端 JS number 丢精度。
 * 反序列化保持 Jackson 默认：字符串 "12.30" 和数字 12.30 都接收；非法字符串由 GlobalExceptionHandler 返回 400。
 */
@Configuration
public class JacksonConfig {
    static final JsonSerializer<BigDecimal> PLAIN_STRING = new JsonSerializer<>() {
        @Override
        public void serialize(BigDecimal v, JsonGenerator g, SerializerProvider p) throws IOException {
            g.writeString(v.toPlainString());
        }
    };

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer bigDecimalAsString() {
        return b -> b.serializerByType(BigDecimal.class, PLAIN_STRING);
    }
}
