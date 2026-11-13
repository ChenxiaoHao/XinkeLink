package cn.xinkelink.realtime;

import cn.xinkelink.auth.AuthPrincipal;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.core.Authentication;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.*;
import org.springframework.web.socket.server.HandshakeInterceptor;
import java.util.Map;

@Configuration(proxyBeanMethods=false)
@Profile("auth")
@EnableWebSocket
@EnableScheduling
public class WebSocketConfiguration implements WebSocketConfigurer {
    private final RealtimeEvents events;
    public WebSocketConfiguration(RealtimeEvents events){this.events=events;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){
        registry.addHandler(events,"/api/v1/ws").addInterceptors(new HandshakeInterceptor(){
            @Override public boolean beforeHandshake(ServerHttpRequest request,ServerHttpResponse response,
                    WebSocketHandler handler,Map<String,Object> attributes){
                if(request.getURI().getRawQuery()!=null||!(request.getPrincipal() instanceof Authentication auth)
                        ||!(auth.getPrincipal() instanceof AuthPrincipal actor)||!"user".equals(actor.actorType())){
                    response.setStatusCode(HttpStatus.FORBIDDEN);return false;
                }
                String authorization=request.getHeaders().getFirst("Authorization");
                if(authorization==null||!authorization.regionMatches(true,0,"Bearer ",0,7)){
                    response.setStatusCode(HttpStatus.UNAUTHORIZED);return false;
                }
                attributes.put("actor",actor);attributes.put("bearer",authorization.substring(7));return true;
            }
            @Override public void afterHandshake(ServerHttpRequest request,ServerHttpResponse response,
                    WebSocketHandler handler,Exception exception){}
        });
    }
}
