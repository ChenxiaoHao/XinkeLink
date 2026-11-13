package cn.xinkelink.common.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record CursorQuery(@Size(max = 1024) String cursor, @Min(1) @Max(50) Integer size) {
    public CursorQuery { size = size == null ? 20 : size; }
}
