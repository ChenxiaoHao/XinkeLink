package cn.xinkelink.admin;

import java.util.List;

public final class AdminModels {
    private AdminModels() {}
    public record Item(String id,Long expectedVersion) {}
    public record Batch(List<Item> items,String targetStatus,String reason,Boolean confirmed) {}
}
