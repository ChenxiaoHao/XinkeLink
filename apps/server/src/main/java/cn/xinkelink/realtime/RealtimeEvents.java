package cn.xinkelink.realtime;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.token.JwtTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import static cn.xinkelink.content.ContentSupport.map;

/** 单实例提交后事件；推送失败由HTTP补取，不能反向破坏已经提交的业务。 */
@Component
@Profile("auth")
public class RealtimeEvents extends TextWebSocketHandler {
    private static final Logger LOG=LoggerFactory.getLogger(RealtimeEvents.class);
    private record Client(WebSocketSession socket,String bearer,long userId) {
        @Override public String toString(){return "Client[userId="+userId+"]";}
    }
    private final Map<String,Client> clients=new ConcurrentHashMap<>();
    private final AuthService auth;
    private final JwtTokenService jwt;
    private final JsonMapper json;
    public RealtimeEvents(AuthService auth,JwtTokenService jwt,JsonMapper json){this.auth=auth;this.jwt=jwt;this.json=json;}

    public void afterCommit(Runnable callback){
        if(TransactionSynchronizationManager.isActualTransactionActive()){
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCommit(){safely(callback);}
            });
        }else safely(callback);
    }
    private void safely(Runnable callback){try{callback.run();}catch(RuntimeException ignored){LOG.warn("实时事件暂未推送，请通过HTTP补取");}}

    public void publish(long userId,String type,Map<String,Object> payload){
        Map<String,Object> event=map("eventId",UUID.randomUUID().toString(),"type",type,"occurredAt",Instant.now(),"payload",payload);
        if(payload.containsKey("conversationId"))event.put("conversationId",payload.get("conversationId"));
        if(payload.containsKey("sequence"))event.put("sequence",payload.get("sequence"));
        String body=json.writeValueAsString(event);
        for(Client client:clients.values())if(client.userId()==userId&&valid(client)){
            try{client.socket().sendMessage(new TextMessage(body));}
            catch(Exception ignored){close(client,CloseStatus.SESSION_NOT_RELIABLE);}
        }
    }
    @Override public void afterConnectionEstablished(WebSocketSession socket){
        var principal=(AuthPrincipal)socket.getAttributes().get("actor");
        String bearer=(String)socket.getAttributes().remove("bearer");
        socket.setTextMessageSizeLimit(1024);socket.setBinaryMessageSizeLimit(1024);
        Client client=new Client(new ConcurrentWebSocketSessionDecorator(socket,5000,131072),bearer,principal.subjectId());
        if(valid(client))clients.put(socket.getId(),client);
    }
    // 这里只接受标准Ping/Pong；客户端不能自行订阅频道或绕过HTTP发业务消息。
    @Override protected void handleTextMessage(WebSocketSession socket,TextMessage message){
        Client client=clients.get(socket.getId());if(client!=null)close(client,CloseStatus.POLICY_VIOLATION);
    }
    @Override public void afterConnectionClosed(WebSocketSession socket,CloseStatus status){clients.remove(socket.getId());}
    @Override public void handleTransportError(WebSocketSession socket,Throwable exception){
        Client client=clients.get(socket.getId());if(client!=null)close(client,CloseStatus.SESSION_NOT_RELIABLE);
    }
    @Scheduled(fixedDelay=1000)
    public void checkSessions(){for(Client client:clients.values())valid(client);}
    private boolean valid(Client client){
        try{AuthPrincipal actor=auth.authenticate(jwt.decode(client.bearer()));
            if(!"user".equals(actor.actorType())||actor.subjectId()!=client.userId())throw new IllegalStateException();
            return client.socket().isOpen();
        }catch(RuntimeException ignored){close(client,CloseStatus.POLICY_VIOLATION);return false;}
    }
    private void close(Client client,CloseStatus status){
        clients.remove(client.socket().getId());try{client.socket().close(status);}catch(Exception ignored){/* 不记录票据或异常原文。 */}
    }
}
