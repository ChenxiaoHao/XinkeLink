package cn.xinkelink.auth.persistence;

import java.time.Instant;

public record UserAccount(long id, String nickname, Long avatarImageId, String bio,
        String major, String className, String enrollmentYear, String banState,
        Instant banUntil, boolean restrictPublish, boolean restrictComment,
        boolean restrictMessage, Instant deletedAt, long version) {}
