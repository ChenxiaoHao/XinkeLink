package cn.xinkelink.auth.persistence;

import java.time.Instant;

public record AdminAccount(long id, String loginName, String passwordHash, String displayName,
        String role, String status, Instant passwordChangedAt, Instant lastLoginAt) {
    @Override public String toString() { return "AdminAccount[id=" + id + ", passwordHash=已隐藏]"; }
}
