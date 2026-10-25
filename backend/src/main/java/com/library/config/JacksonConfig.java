package com.library.config;

import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 日期格式化配置
 * 说明：Spring Boot 3 对 LocalDateTime / LocalDate 默认按 ISO 格式序列化（如 2026-01-01T09:00:00），
 * 与前端表格展示习惯不符。此处统一为 "yyyy-MM-dd HH:mm:ss" / "yyyy-MM-dd"，保证前后端日期格式一致。
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATETIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return builder -> {
            builder.serializers(
                    new LocalDateTimeSerializer(DATETIME_FORMATTER),
                    new LocalDateSerializer(DATE_FORMATTER)
            );
            builder.deserializers(
                    new LocalDateTimeDeserializer(DATETIME_FORMATTER),
                    new LocalDateDeserializer(DATE_FORMATTER)
            );
        };
    }
}
