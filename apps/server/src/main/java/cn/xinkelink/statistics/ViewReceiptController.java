package cn.xinkelink.statistics;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.io.IOException;
import java.util.*;

@RestController @Profile("auth")
public class ViewReceiptController {
    private static final DefaultRedisScript<Long> QUOTA=new DefaultRedisScript<>("local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n",Long.class);
    private final ViewReceiptService receipts;private final VisitorCredentials credentials;private final ViewStatisticsProperties config;private final JsonMapper json;private final StringRedisTemplate redis;private final MeterRegistry meters;
    public ViewReceiptController(ViewReceiptService r,VisitorCredentials c,ViewStatisticsProperties p,JsonMapper j,StringRedisTemplate redis,MeterRegistry m){receipts=r;credentials=c;config=p;json=j;this.redis=redis;meters=m;}
    @PostMapping(value="/api/v1/statistics/anonymous-credential",consumes="application/json")
    public ApiResponse<VisitorCredentials.Issued> credential(HttpServletRequest request,@AuthenticationPrincipal AuthPrincipal actor){
        if(!config.isEnabled())throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
        if(actor!=null)throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        JsonNode body=body(request);if(!body.isObject()||body.size()!=0)invalid();
        quota("credential",credentials.quota(request.getRemoteAddr()),60);
        return ApiResponse.success(credentials.issue());
    }
    @PostMapping(value="/api/v1/statistics/post-views",consumes="application/json") @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Map<String,Object>> receive(HttpServletRequest request,@AuthenticationPrincipal AuthPrincipal actor){
        Timer.Sample timer=Timer.start(meters);String outcome="FAILED";
        try {
            JsonNode body=body(request);if(!body.isObject())invalid();
            Set<String> fields=new HashSet<>();body.propertyNames().forEach(fields::add);
            if(!Set.of("version","eventId","postId","anonymousCredential").containsAll(fields)||!body.path("version").isInt()||!body.path("eventId").isString()||!body.path("postId").isString()
                    ||body.has("anonymousCredential")&&!body.path("anonymousCredential").isString())invalid();
            quota("address",credentials.quota(request.getRemoteAddr()),2000);
            String anonymous=body.has("anonymousCredential")?body.path("anonymousCredential").asString():null;
            String quotaIdentity=actor==null?credentials.anonymous(anonymous):credentials.user(actor.subjectId());
            quota("visitor",quotaIdentity,600);
            var result=receipts.receive(new ViewReceiptService.Input(body.path("version").asInt(),body.path("eventId").asString(),body.path("postId").asString(),anonymous),actor);
            outcome="ACCEPTED";return ApiResponse.success(result);
        }catch(BusinessException e){outcome=e.code().status().is4xxClientError()?"REJECTED":"FAILED";throw e;}
        finally{meters.counter("xinkelink.view.http","result",outcome).increment();timer.stop(meters.timer("xinkelink.view.http.duration","result",outcome));}
    }
    private JsonNode body(HttpServletRequest request){
        try{byte[] bytes=request.getInputStream().readNBytes(2049);if(bytes.length==0||bytes.length>2048)invalid();return json.readTree(bytes);}
        catch(IOException|tools.jackson.core.JacksonException e){invalid();return null;}
    }
    private void quota(String operation,String key,int max){
        Long n;try{n=redis.execute(QUOTA,List.of("xl:view:quota:"+operation+":"+key));}catch(RuntimeException e){throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);}
        if(n==null||n<=0)throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);if(n>max)throw new BusinessException(ErrorCode.RATE_LIMITED);
    }
    private static void invalid(){throw new BusinessException(ErrorCode.VALIDATION_FAILED);}
}
