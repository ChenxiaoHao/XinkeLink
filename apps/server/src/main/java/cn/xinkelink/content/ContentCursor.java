package cn.xinkelink.content;

import cn.xinkelink.auth.token.TokenProperties;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Component
@Profile("auth")
public class ContentCursor {
    public record Position(Instant snapshot,Instant at,long id,Long value){}
    private record Payload(String scope,String viewer,String snapshot,String at,long id,Long value){}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final byte[] key;
    private final Clock clock;
    private final ContentMapper mapper;
    public ContentCursor(TokenProperties properties,Clock clock){this(properties,clock,null);}
    @Autowired
    public ContentCursor(TokenProperties properties,Clock clock,ContentMapper mapper){
        this.key=Base64.getDecoder().decode(properties.secretBase64());this.clock=clock;this.mapper=mapper;
    }

    public Position read(String token,String scope,Long viewer){
        // 列表上界和行创建时间使用同一数据库时钟，避免几毫秒偏差漏掉刚发布的内容。
        Instant now=mapper==null?clock.instant().truncatedTo(ChronoUnit.MILLIS):mapper.currentTime();
        if(token==null)return new Position(now,null,0,null);
        try{
            if(token.length()>4096||!token.matches("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))throw invalid();
            String[] parts=token.split("\\.");
            if(!MessageDigest.isEqual(sign(parts[0]),Base64.getUrlDecoder().decode(parts[1])))throw invalid();
            Payload payload=JSON.readValue(Base64.getUrlDecoder().decode(parts[0]),Payload.class);
            Instant snapshot=Instant.parse(payload.snapshot());
            Instant at=Instant.parse(payload.at());
            if(!payload.scope().equals(scope)||!payload.viewer().equals(viewer==null?"guest":viewer.toString())
                    ||snapshot.isAfter(now)||!snapshot.plusSeconds(3600).isAfter(now)
                    ||at.isAfter(snapshot)||payload.id()<1||payload.value()!=null&&payload.value()<0)throw invalid();
            return new Position(snapshot,at,payload.id(),payload.value());
        }catch(RuntimeException exception){throw invalid();}
    }
    public String next(String scope,Long viewer,Position position){
        Payload payload=new Payload(scope,viewer==null?"guest":viewer.toString(),position.snapshot().toString(),position.at().toString(),position.id(),position.value());
        String body=Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(payload));
        return body+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(sign(body));
    }
    private byte[] sign(String body){
        try{
            Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));
            return mac.doFinal(("xinkelink:cursor:v1:"+body).getBytes(StandardCharsets.UTF_8));
        }catch(java.security.GeneralSecurityException exception){throw new IllegalStateException("游标签名不可用");}
    }
    private BusinessException invalid(){return new BusinessException(ErrorCode.CURSOR_INVALID);}
}
