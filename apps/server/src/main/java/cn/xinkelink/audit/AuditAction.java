package cn.xinkelink.audit;

import java.lang.annotation.*;

/** 标记管理命令边界；只记录字段名和结果，不采集请求正文。 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AuditAction {
    String module();
    String action();
    String targetType();
    String[] fields() default {};
}
