package cn.xinkelink.common.api;

import java.util.List;

public record CursorPage<T>(List<T> items, String nextCursor, boolean hasMore) {
    public CursorPage { items = List.copyOf(items); }
}
