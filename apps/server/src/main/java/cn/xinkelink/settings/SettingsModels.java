package cn.xinkelink.settings;

import jakarta.validation.constraints.*;
import java.util.List;

public final class SettingsModels {
    private SettingsModels() {}
    public record Review(@NotNull Boolean sensitiveWordIntercept,@NotNull Boolean nightQueueMode,
            @NotBlank String imageSensitivity,@NotNull @Size(max=30) List<@NotBlank @Size(min=2,max=20) String> sensitiveWords,
            @NotNull @Min(0) Long expectedVersion) {}
    public record Reset(@NotNull @Min(0) Long expectedVersion,@NotNull Boolean confirmed) {}
}
