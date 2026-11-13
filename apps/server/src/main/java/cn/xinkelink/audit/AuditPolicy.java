package cn.xinkelink.audit;

import java.util.*;

public final class AuditPolicy {
    private AuditPolicy() {}
    private static final Set<String> FIELDS=Set.of("status","version","deletedAt","moderationHidden","saleStatus","restrictions",
            "publish","comment","message","banState","banUntil","title","body","category","scope","audience",
            "sensitiveWordIntercept","nightQueueMode","imageSensitivity","sensitiveWords");
    public static List<String> fields(Collection<String> names) {
        // 正文也不记录：仅可审计内容是否变更，正文值从不进入日志。
        return names.stream().filter(FIELDS::contains).filter(name->!name.equals("body")).distinct().toList();
    }
    public static String csv(Object value) {
        String text=value==null?"":String.valueOf(value);
        String stripped=text.stripLeading();
        if(!stripped.isEmpty()&&"=+-@\t\r\n".indexOf(stripped.charAt(0))>=0)text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
}
