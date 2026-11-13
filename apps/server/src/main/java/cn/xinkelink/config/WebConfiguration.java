package cn.xinkelink.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;

@Configuration(proxyBeanMethods = false)
public class WebConfiguration {
    @Bean
    SimpleModule utcTimeModule() {
        DateTimeFormatter format = new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
        SimpleModule module = new SimpleModule("utc-milliseconds");
        module.addSerializer(Instant.class, new ValueSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator generator, SerializationContext context) {
                generator.writeString(format.format(value));
            }
        });
        return module;
    }
}
