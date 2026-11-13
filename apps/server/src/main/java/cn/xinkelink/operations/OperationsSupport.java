package cn.xinkelink.operations;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.content.ContentSupport;
import java.util.List;

final class OperationsSupport {
    private OperationsSupport() {}
    static String text(String value, int max) {
        if(value==null || value.isBlank() || value.length()>max) throw invalid();
        return value.strip();
    }
    static String option(String value, List<String> allowed, boolean optional) {
        if(value==null && optional) return null;
        if(value==null || !allowed.contains(value)) throw invalid();
        return value;
    }
    static String request(String value) {
        if(value==null || !value.matches("[\\x21-\\x7E]{1,64}")) throw invalid();
        return value;
    }
    static void confirm(Long expectedVersion, Boolean confirmed) {
        if(expectedVersion==null || expectedVersion<0 || !Boolean.TRUE.equals(confirmed)) throw invalid();
    }
    static int page(Integer value) { int page=value==null?1:value; if(page<1) throw invalid(); return page; }
    static int pageSize(Integer value) { int size=value==null?10:value; if(!List.of(10,20,50).contains(size)) throw invalid(); return size; }
    static String like(String keyword) {
        String value=ContentSupport.keyword(keyword);
        return value.isEmpty()?null:"%"+value.replace("!","!!").replace("%","!%").replace("_","!_")+"%";
    }
    static BusinessException invalid() { return new BusinessException(ErrorCode.VALIDATION_FAILED); }
}
