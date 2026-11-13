package cn.xinkelink.auth.ratelimit;

public interface AuthRateLimiter {
    // key 使用可信远端地址或服务端身份，不能直接采用客户端转发头。
    void check(String operation, String key);
}
