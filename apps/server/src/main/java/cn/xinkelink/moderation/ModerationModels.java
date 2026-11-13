package cn.xinkelink.moderation;

import java.util.List;

public final class ModerationModels {
    private ModerationModels() {}
    public record CreateReport(String targetType,String targetId,String reasonCode,String description,
                               List<String> evidenceImageIds,String clientRequestId) {}
    public record Resolution(String action,String reason,Long expectedVersion,Boolean confirmed) {}
    public record Restrictions(String channel,Boolean enabled,String reason,Long expectedVersion,Boolean confirmed) {}
    public record Ban(String mode,Integer durationDays,String reason,Long expectedVersion,Boolean confirmed) {}
    public record Release(String reason,Long expectedVersion,Boolean confirmed) {}
}
