package cn.xinkelink.auth.token;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("xinkelink.auth.jwt")
public record TokenProperties(String secretBase64, String issuer) {
    @Override
    public String toString() {
        return "TokenProperties[secretBase64=[REDACTED], issuer=" + issuer + "]";
    }
}
