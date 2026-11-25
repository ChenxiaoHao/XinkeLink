package cn.xinkelink.settings;

import cn.xinkelink.audit.AuditAction;
import cn.xinkelink.auth.*;
import cn.xinkelink.common.api.*;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.content.security.ContentSecurityService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.util.*;
import static cn.xinkelink.content.ContentSupport.map;

@Service @Profile("auth")
public class SettingsService {
    private final JdbcTemplate jdbc;private final ContentSupport support;private final AuthService auth;private final JsonMapper json;private final ContentSecurityService contentSecurity;
    private static final Map<String,Object> DEFAULTS=map("sensitiveWordIntercept",true,"nightQueueMode",true,"imageSensitivity","standard",
            "sensitiveWords",List.of("代写论文","期末代考","英语四六级答案","实验报告定制","兼职加微信","刷课插件","高薪日结打工"));
    public SettingsService(JdbcTemplate jdbc,ContentSupport support,AuthService auth,JsonMapper json) {this(jdbc,support,auth,json,(ContentSecurityService)null);}
    @Autowired
    public SettingsService(JdbcTemplate jdbc,ContentSupport support,AuthService auth,JsonMapper json,ObjectProvider<ContentSecurityService> contentSecurity) {this(jdbc,support,auth,json,contentSecurity.getIfAvailable());}
    private SettingsService(JdbcTemplate jdbc,ContentSupport support,AuthService auth,JsonMapper json,ContentSecurityService contentSecurity) {this.jdbc=jdbc;this.support=support;this.auth=auth;this.json=json;this.contentSecurity=contentSecurity;}
    @Transactional(readOnly=true)
    public Map<String,Object> read(AuthPrincipal principal) {support.requireAdmin(principal);return view(principal,List.of());}
    @Transactional
    @AuditAction(module="settings",action="save",targetType="configuration")
    public Map<String,Object> save(SettingsModels.Review input,AuthPrincipal principal) {
        long admin=support.requireAdmin(principal);
        if(input.sensitiveWordIntercept()==null||input.nightQueueMode()==null||!Set.of("relaxed","standard","strict").contains(input.imageSensitivity())
                ||input.sensitiveWords()==null||input.sensitiveWords().size()>30)throw invalid();
        List<String> words=input.sensitiveWords().stream().map(word->{
            if(word==null||word.strip().length()<2||word.strip().length()>20)throw invalid();return word.strip();
        }).distinct().toList();
        return write(admin,input.expectedVersion(),map("sensitiveWordIntercept",input.sensitiveWordIntercept(),"nightQueueMode",input.nightQueueMode(),
                "imageSensitivity",input.imageSensitivity(),"sensitiveWords",words),principal);
    }
    @Transactional
    @AuditAction(module="settings",action="reset",targetType="configuration")
    public Map<String,Object> reset(SettingsModels.Reset input,AuthPrincipal principal) {
        long admin=support.requireAdmin(principal);if(!Boolean.TRUE.equals(input.confirmed()))throw invalid();
        return write(admin,input.expectedVersion(),DEFAULTS,principal);
    }
    private Map<String,Object> write(long admin,Long expected,Map<String,Object> next,AuthPrincipal principal) {
        // 并发首次保存先建立默认行，再锁同一配置行；不覆盖已有配置。
        jdbc.update("INSERT IGNORE INTO xl_system_setting(setting_key,value_json) VALUES ('content_review',?)",json.writeValueAsString(DEFAULTS));
        var row=jdbc.queryForMap("SELECT * FROM xl_system_setting WHERE setting_key='content_review' FOR UPDATE");
        ContentSupport.version(((Number)row.get("version")).longValue(),expected);
        Map<String,Object> current=decode(row.get("value_json"));
        List<String> changed=DEFAULTS.keySet().stream().filter(key->!Objects.equals(current.get(key),next.get(key))).toList();
        if(!changed.isEmpty()){jdbc.update("UPDATE xl_system_setting SET value_json=?,updated_by=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE setting_key='content_review'",
                json.writeValueAsString(next),admin);
            if(contentSecurity!=null)contentSecurity.invalidateSettingsCache();}
        return view(principal,changed);
    }
    private Map<String,Object> view(AuthPrincipal principal,List<String> changed) {
        var rows=jdbc.queryForList("SELECT * FROM xl_system_setting WHERE setting_key='content_review'");
        var row=rows.isEmpty()?null:rows.getFirst();
        Map<String,Object> review=row==null?DEFAULTS:decode(row.get("value_json"));
        boolean sensitiveActive=!Boolean.FALSE.equals(review.get("sensitiveWordIntercept"));
        boolean wechatActive=contentSecurity!=null&&contentSecurity.isWechatEnabled();
        return map("platform",map("name","信科 Link","school","河南信息科技学院","description","学生校园社区，非学校官方平台"),
                "contentReview",review,"effectMode",sensitiveActive||wechatActive?"active_text_review":"configuration_only",
                "community",map("controlsAvailable",false,"description","遵循已确认的社区规则，不提供未设计的操作控件"),
                "administrator",auth.currentAdmin(principal),"system",map("architecture","模块化单体","externalPush",false,"automaticReview",sensitiveActive||wechatActive),
                "version",row==null?0:row.get("version"),"updatedAt",row==null?null:ContentSupport.instant(row.get("updated_at")),"changedFields",changed);
    }
    @SuppressWarnings("unchecked") private Map<String,Object> decode(Object value) {
        return value instanceof byte[] bytes?json.readValue(bytes,Map.class):json.readValue(String.valueOf(value),Map.class);
    }
    private static BusinessException invalid() {return new BusinessException(ErrorCode.VALIDATION_FAILED);}
}
