package cn.xinkelink.auth.persistence;

import java.time.Instant;

public record AuthSession(long id, String subjectType, long subjectId, byte[] refreshHash,
        int refreshGeneration, byte[] csrfHash, Instant expiresAt, Instant revokedAt,
        Instant lastUsedAt, long version) {}
