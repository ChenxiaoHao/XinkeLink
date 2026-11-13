package cn.xinkelink.config;

import cn.xinkelink.auth.*;
import cn.xinkelink.auth.persistence.AuthMapper;
import cn.xinkelink.auth.ratelimit.AuthRateLimiter;
import cn.xinkelink.auth.ratelimit.RedisAuthRateLimiter;
import cn.xinkelink.auth.token.JwtTokenService;
import cn.xinkelink.auth.token.TokenProperties;
import cn.xinkelink.auth.wechat.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile("auth")
@EnableConfigurationProperties({TokenProperties.class, WechatProperties.class, AdminSecurityProperties.class})
public class AuthConfiguration {
    @Bean Clock authClock() { return Clock.systemUTC(); }
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean JwtTokenService jwtTokenService(TokenProperties properties, Clock clock) { return new JwtTokenService(properties, clock); }
    @Bean AuthService authService(AuthMapper mapper, JwtTokenService tokens, PasswordEncoder passwords, Clock clock,cn.xinkelink.media.MediaService media) {
        return new AuthService(mapper, tokens, passwords, clock,media);
    }
    @Bean AuthJwtAuthenticator authJwtAuthenticator(AuthService service,
            org.springframework.beans.factory.ObjectProvider<cn.xinkelink.moderation.BanExpiryService> bans) {
        return new AuthJwtAuthenticator(service,id->bans.ifAvailable(expiry->expiry.normalize(id)));
    }
    @Bean AdminBrowserSecurity adminBrowserSecurity(AdminSecurityProperties properties, Clock clock) { return new AdminBrowserSecurity(properties, clock); }
    @Bean AuthRateLimiter authRateLimiter(StringRedisTemplate redis) { return new RedisAuthRateLimiter(redis); }
    @Bean WechatCodeClient wechatCodeClient(WechatProperties properties) {
        // 微信密钥只能发往固定目标；明确关闭重定向并限制网络等待时间。
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        return new OfficialWechatCodeClient(properties, RestClient.builder().requestFactory(factory).build());
    }
}
