package cn.xinkelink.operations;

import jakarta.validation.constraints.*;
import java.time.Instant;

public final class OperationsModels {
    private OperationsModels() {}

    public record CreateAnnouncement(@NotBlank @Size(max=60) String title, @NotBlank @Size(max=1200) String body,
            @NotNull @Pattern(regexp="community|activity|safety") String category,
            @NotNull @Pattern(regexp="all|forum") String scope,
            @NotNull @Pattern(regexp="[\\x21-\\x7E]{1,64}") String clientRequestId) {}
    public record EditAnnouncement(@NotBlank @Size(max=60) String title, @NotBlank @Size(max=1200) String body,
            @NotNull @Pattern(regexp="community|activity|safety") String category,
            @NotNull @Pattern(regexp="all|forum") String scope,
            @NotNull @PositiveOrZero Long expectedVersion) {}
    public record ConfirmCommand(@NotNull @PositiveOrZero Long expectedVersion, @NotNull Boolean confirmed) {}
    public record CreateTask(@NotBlank @Size(max=50) String title, @NotBlank @Size(max=500) String body,
            @NotNull @Pattern(regexp="all|active|recent") String audience,
            @NotNull @Pattern(regexp="[\\x21-\\x7E]{1,64}") String clientRequestId) {}
    public record EditTask(@NotBlank @Size(max=50) String title, @NotBlank @Size(max=500) String body,
            @NotNull @Pattern(regexp="all|active|recent") String audience,
            @NotNull @PositiveOrZero Long expectedVersion) {}
    public record AudiencePreview(@NotNull @Pattern(regexp="all|active|recent") String audience) {}
    public record SendTask(@NotNull @PositiveOrZero Long expectedVersion,
            @NotNull @Pattern(regexp="[a-f0-9]{64}") String audienceFingerprint, @NotNull Boolean confirmed) {}

    record AnnouncementRow(long id, String title, String body, String category, String scope, String status,
            long createdBy, long updatedBy, byte[] requestHash, Instant firstPublishedAt, Instant lastPublishedAt,
            Instant withdrawnAt, Instant createdAt, Instant updatedAt, long version, Instant deletedAt) {}
    record TaskRow(long id, String title, String body, String audience, String status, long createdBy,
            long updatedBy, byte[] requestHash, Instant sentAt, Integer recipientCount,
            Instant createdAt, Instant updatedAt, long version) {}
    record AudienceUser(long id, String banState, Instant banUntil, boolean restrictPublish,
            boolean restrictComment, boolean restrictMessage, Instant lastActiveAt) {}
}
