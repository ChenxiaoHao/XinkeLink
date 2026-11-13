package cn.xinkelink.integration;

import cn.xinkelink.XinkeLinkApplication;
import cn.xinkelink.auth.wechat.*;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.util.*;

/** 仅测试classpath可启动：真实数据库/Redis/HTTP，微信换码使用明确的测试适配器。 */
public class Day5IntegrationServer {
    public static void main(String[] args) throws Exception {
        var context=new SpringApplicationBuilder(XinkeLinkApplication.class,WechatAdapter.class).profiles("db","auth","redis").run(args);
        Path path=Path.of(System.getenv("XINKELINK_DAY5_PRIVATE")).resolve("browser-account.json");
        JsonMapper json=context.getBean(JsonMapper.class);JdbcTemplate jdbc=context.getBean(JdbcTemplate.class);
        if(!Files.exists(path)) {
            byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);
            String account="day5-review-"+UUID.randomUUID().toString().substring(0,8),password=Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
            jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES (?,?,?,UTC_TIMESTAMP(3))",
                    account,context.getBean(PasswordEncoder.class).encode(password),"本地联调管理员");
            Files.writeString(path,json.writeValueAsString(Map.of("account",account,"password",password,"origin","https://localhost:5173")),StandardOpenOption.CREATE_NEW);
        }
        System.out.println("DAY5_READY：仅本地联调；微信换码为测试适配器，非真实微信验收");
    }
    @TestConfiguration(proxyBeanMethods=false)
    static class WechatAdapter {
        @Bean @Primary WechatCodeClient integrationWechatCodeClient() {
            return code->{
                if(!Set.of("day5-alice","day5-bob","day5-charlie").contains(code))
                    throw new cn.xinkelink.common.api.BusinessException(cn.xinkelink.common.api.ErrorCode.WECHAT_LOGIN_FAILED);
                return new WechatIdentity("day5-test-adapter",code);
            };
        }
    }
}
