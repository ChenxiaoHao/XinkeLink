package cn.xinkelink.content;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.auth.persistence.AuthMapper;
import cn.xinkelink.auth.persistence.UserAccount;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Component
@Profile("auth")
public class ContentSupport {
    private static final JsonMapper JSON=JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();
    private final AuthMapper auth;
    private final ContentMapper mapper;
    private cn.xinkelink.media.MediaService media;
    public ContentSupport(AuthMapper auth,ContentMapper mapper){this.auth=auth;this.mapper=mapper;}
    @org.springframework.beans.factory.annotation.Autowired
    public ContentSupport(AuthMapper auth,ContentMapper mapper,cn.xinkelink.media.MediaService media){this(auth,mapper);this.media=media;}

    // JWT只确认身份；每次业务写入仍读取当前账号及独立通道限制。
    public long require(AuthPrincipal principal,String channel){
        if(principal==null)throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if(!"user".equals(principal.actorType()))throw new BusinessException(ErrorCode.FORBIDDEN);
        UserAccount user=TransactionSynchronizationManager.isActualTransactionActive()
                &&!TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                ?mapper.userForUpdate(principal.subjectId()):auth.findUser(principal.subjectId());
        if(user==null||user.deletedAt()!=null)throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if("permanent".equals(user.banState())||"temporary".equals(user.banState())&&user.banUntil()!=null
                &&user.banUntil().isAfter(Instant.now()))throw new BusinessException(ErrorCode.ACCOUNT_BANNED);
        if("publish".equals(channel)&&user.restrictPublish()||"comment".equals(channel)&&user.restrictComment()
                ||"message".equals(channel)&&user.restrictMessage())throw new BusinessException(ErrorCode.ACTION_RESTRICTED);
        return user.id();
    }
    public Long viewer(AuthPrincipal principal){return principal!=null&&"user".equals(principal.actorType())?principal.subjectId():null;}
    public long requireAdmin(AuthPrincipal principal){
        if(principal==null)throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if(!"admin".equals(principal.actorType()))throw new BusinessException(ErrorCode.FORBIDDEN);
        var admin=auth.findAdmin(principal.subjectId());
        if(admin==null||!"active".equals(admin.status())||!"ADMIN".equals(admin.role()))
            throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        return admin.id();
    }
    public Map<Long,Map<String,Object>> users(Collection<Long> ids){
        Map<Long,Map<String,Object>> result=new HashMap<>();
        if(ids.isEmpty())return result;
        var avatars=media==null?Map.<Long,Map<String,Object>>of():media.avatars(ids);
        for(UserAccount user:mapper.users(new HashSet<>(ids)))result.put(user.id(),map("id",user.deletedAt()==null?Long.toString(user.id()):null,
                "nickname",user.deletedAt()==null?user.nickname():"已注销用户","avatar",user.deletedAt()==null?avatars.get(user.id()):null,
                "major",user.deletedAt()==null?user.major():"","className",user.deletedAt()==null?user.className():"","selfReported",true));
        return result;
    }
    public static long id(String value){
        if(value==null||!value.matches("[1-9][0-9]{0,18}"))throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        try{return Long.parseLong(value);}catch(NumberFormatException ignored){throw new BusinessException(ErrorCode.VALIDATION_FAILED);}
    }
    public static int size(Integer value){int size=value==null?20:value;if(size<1||size>50)throw new BusinessException(ErrorCode.VALIDATION_FAILED);return size;}
    public static String keyword(String value){
        String text=value==null?"":value.strip();
        if(text.length()>60)throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return text;
    }
    public static byte[] hash(Object value){
        try{return MessageDigest.getInstance("SHA-256").digest(JSON.writeValueAsBytes(value));}
        catch(NoSuchAlgorithmException exception){throw new IllegalStateException("当前环境缺少SHA256");}
    }
    public static Map<String,Object> map(Object...entries){
        Map<String,Object> values=new LinkedHashMap<>();
        for(int i=0;i<entries.length;i+=2)values.put((String)entries[i],entries[i+1]);
        return values;
    }
    public static Instant instant(Object value) {
        if(value==null)return null;
        if(value instanceof Instant instant)return instant;
        if(value instanceof java.sql.Timestamp timestamp)return timestamp.toInstant();
        return ((java.time.LocalDateTime)value).toInstant(java.time.ZoneOffset.UTC);
    }
    public static void version(long actual,Long expected){
        if(expected==null||expected<0)throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if(actual!=expected)throw new BusinessException(ErrorCode.VERSION_CONFLICT);
    }
    public static long ifMatch(String header){
        if(header==null)throw new BusinessException(ErrorCode.PRECONDITION_REQUIRED);
        if(!header.matches("\"(0|[1-9][0-9]{0,18})\""))throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        try{return Long.parseLong(header.substring(1,header.length()-1));}
        catch(NumberFormatException ignored){throw new BusinessException(ErrorCode.VALIDATION_FAILED);}
    }
}
