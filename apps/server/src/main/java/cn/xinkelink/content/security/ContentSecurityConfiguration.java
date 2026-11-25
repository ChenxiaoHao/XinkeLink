package cn.xinkelink.content.security;

import cn.xinkelink.auth.wechat.WechatProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile("auth")
@EnableConfigurationProperties(ContentSecurityProperties.class)
public class ContentSecurityConfiguration {
    @Bean
    @ConditionalOnMissingBean(WechatContentSecurityClient.class)
    WechatContentSecurityClient wechatContentSecurityClient(
            WechatProperties wechat,
            ContentSecurityProperties properties,
            ObjectProvider<StringRedisTemplate> redis,
            JsonMapper json) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new OfficialWechatContentSecurityClient(
                wechat,
                properties,
                RestClient.builder().requestFactory(factory).build(),
                redis.getIfAvailable(),
                json);
    }
}
