package cn.xinkelink.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public final class AuthModels {
    private AuthModels() {}

    public record WechatLogin(@NotBlank @Size(max = 256) String code) {
        @Override public String toString() { return "WechatLogin[code=已隐藏]"; }
    }
    public record Refresh(@NotBlank @Size(max = 128) String refreshToken) {
        @Override public String toString() { return "Refresh[refreshToken=已隐藏]"; }
    }
    public record AdminLogin(@NotBlank @Size(max = 64) String account, @NotBlank @Size(max = 72) String password) {
        @Override public String toString() { return "AdminLogin[password=已隐藏]"; }
    }
    public record Restrictions(boolean publish, boolean comment, boolean message) {}
    public record Me(String id, String nickname, Object avatar, String bio, String major, String className,
            String enrollmentYear, boolean selfReported, String status, Restrictions restrictions, long version) {}
    public record AdminMe(String id, String loginName, String displayName, String role) {}
    public record UserTokens(String accessToken, long expiresIn, String refreshToken, long refreshExpiresIn, Me me) {
        @Override public String toString() { return "UserTokens[凭据已隐藏]"; }
    }
    public record AdminTokens(String accessToken, long expiresIn, String csrfToken, AdminMe admin) {
        @Override public String toString() { return "AdminTokens[凭据已隐藏]"; }
    }
    public record AdminSession(AdminTokens tokens, String refreshToken, Instant expiresAt) {
        @Override public String toString() { return "AdminSession[凭据已隐藏]"; }
    }
    public record Logout(boolean revoked) {}
}
