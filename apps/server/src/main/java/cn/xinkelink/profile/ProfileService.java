package cn.xinkelink.profile;

import cn.xinkelink.auth.*;
import cn.xinkelink.auth.persistence.AuthMapper;
import cn.xinkelink.common.api.*;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.content.security.ContentScene;
import cn.xinkelink.content.security.ContentSecurityService;
import cn.xinkelink.media.MediaService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import java.util.*;
import static cn.xinkelink.content.ContentSupport.map;

@Service @Profile("auth")
public class ProfileService {
    private final JdbcTemplate jdbc;private final ContentSupport support;private final AuthMapper mapper;private final AuthService auth;private final MediaService media;private final ContentSecurityService contentSecurity;
    public ProfileService(JdbcTemplate jdbc,ContentSupport support,AuthMapper mapper,AuthService auth,MediaService media) {this(jdbc,support,mapper,auth,media,(ContentSecurityService)null);}
    @Autowired
    public ProfileService(JdbcTemplate jdbc,ContentSupport support,AuthMapper mapper,AuthService auth,MediaService media,ObjectProvider<ContentSecurityService> contentSecurity) {this(jdbc,support,mapper,auth,media,contentSecurity.getIfAvailable());}
    private ProfileService(JdbcTemplate jdbc,ContentSupport support,AuthMapper mapper,AuthService auth,MediaService media,ContentSecurityService contentSecurity) {this.jdbc=jdbc;this.support=support;this.mapper=mapper;this.auth=auth;this.media=media;this.contentSecurity=contentSecurity;}
    @Transactional
    public AuthModels.Me update(JsonNode input,AuthPrincipal principal) {
        long id=support.require(principal,null);check(input,Set.of("nickname","bio","major","className","enrollmentYear","avatarImageId","expectedVersion"));
        var current=mapper.findUser(id);ContentSupport.version(current.version(),version(input));
        String nickname=text(input,"nickname",current.nickname(),20,false),bio=text(input,"bio",current.bio(),40,true),
                major=text(input,"major",current.major(),60,true),className=text(input,"className",current.className(),40,true),year=current.enrollmentYear();
        if(contentSecurity!=null)contentSecurity.checkText(id,ContentScene.PROFILE,nickname,bio,major,className);
        if(input.has("enrollmentYear")){year=input.get("enrollmentYear").isNull()?null:text(input,"enrollmentYear",year,4,false);if(year!=null&&!year.matches("(19|20)[0-9]{2}"))throw invalid();}
        Long avatar=current.avatarImageId();
        if(input.has("avatarImageId"))avatar=input.get("avatarImageId").isNull()?null:ContentSupport.id(text(input,"avatarImageId",null,19,false));
        if(!Objects.equals(avatar,current.avatarImageId()))media.bindAvatar(id,avatar);
        if(!Objects.equals(nickname,current.nickname())||!Objects.equals(bio,current.bio())||!Objects.equals(major,current.major())||!Objects.equals(className,current.className())
                ||!Objects.equals(year,current.enrollmentYear())||!Objects.equals(avatar,current.avatarImageId()))
            jdbc.update("UPDATE xl_user SET nickname=?,bio=?,major=?,class_name=?,enrollment_year=?,avatar_image_id=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=?",
                    nickname,bio,major,className,year,avatar,id);
        return auth.currentUser(principal);
    }
    public Map<String,Object> preferences(AuthPrincipal principal) {
        long id=support.require(principal,null);var rows=jdbc.queryForList("SELECT messages,interactions,announcements,sound,version FROM xl_user_preference WHERE user_id=?",id);
        if(rows.isEmpty())return map("messages",true,"interactions",true,"announcements",true,"sound",false,"version",0);
        var row=rows.getFirst();return map("messages",bool(row.get("messages")),"interactions",bool(row.get("interactions")),"announcements",bool(row.get("announcements")),"sound",bool(row.get("sound")),"version",row.get("version"));
    }
    @Transactional
    public Map<String,Object> preferences(JsonNode input,AuthPrincipal principal) {
        long id=support.require(principal,null);check(input,Set.of("messages","interactions","announcements","sound","expectedVersion"));
        var current=preferences(principal);ContentSupport.version(((Number)current.get("version")).longValue(),version(input));
        Map<String,Object> next=new LinkedHashMap<>(current);
        for(String key:List.of("messages","interactions","announcements","sound"))if(input.has(key)) {
            if(!input.get(key).isBoolean())throw invalid();next.put(key,input.get(key).booleanValue());
        }
        if(!next.equals(current))jdbc.update("""
                INSERT INTO xl_user_preference(user_id,messages,interactions,announcements,sound,version) VALUES (?,?,?,?,?,1)
                ON DUPLICATE KEY UPDATE messages=?,interactions=?,announcements=?,sound=?,updated_at=UTC_TIMESTAMP(3),version=version+1
                """,id,next.get("messages"),next.get("interactions"),next.get("announcements"),next.get("sound"),next.get("messages"),next.get("interactions"),next.get("announcements"),next.get("sound"));
        return preferences(principal);
    }
    @Transactional
    public Map<String,Object> block(long target,boolean blocked,AuthPrincipal principal) {
        long id=support.require(principal,null);if(target==id)throw invalid();
        if(blocked){if(mapper.findUser(target)==null||mapper.findUser(target).deletedAt()!=null)throw new BusinessException(ErrorCode.NOT_FOUND);
            jdbc.update("INSERT IGNORE INTO xl_user_block(owner_id,blocked_user_id) VALUES (?,?)",id,target);
        }else jdbc.update("DELETE FROM xl_user_block WHERE owner_id=? AND blocked_user_id=?",id,target);
        return map("userId",Long.toString(target),"blocked",blocked);
    }
    private static boolean bool(Object value) {return value instanceof Boolean flag?flag:((Number)value).intValue()!=0;}
    private static void check(JsonNode value,Set<String> fields) {
        if(value==null||!value.isObject()||value.properties().stream().anyMatch(entry->!fields.contains(entry.getKey())))throw invalid();
    }
    private static Long version(JsonNode input) {
        JsonNode version=input.get("expectedVersion");if(version==null||!version.isIntegralNumber()||!version.canConvertToLong()||version.longValue()<0)throw invalid();return version.longValue();
    }
    private static String text(JsonNode input,String key,String fallback,int max,boolean empty) {
        if(!input.has(key))return fallback;JsonNode node=input.get(key);
        if(!node.isString())throw invalid();String value=node.stringValue().strip();
        if(value.length()>max||!empty&&value.isEmpty())throw invalid();return value;
    }
    private static BusinessException invalid() {return new BusinessException(ErrorCode.VALIDATION_FAILED);}
}
