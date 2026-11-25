package cn.xinkelink.messaging;

import cn.xinkelink.auth.AuthService;
import cn.xinkelink.auth.wechat.WechatIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"db", "auth"})
@Transactional
class MessagingHttpIT {

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    String alice, bob, outsider, aToken, bToken, oToken;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("xinkelink.auth.jwt.secret-base64", () -> Base64.getEncoder().encodeToString(new byte[32]));
    }

    @BeforeEach
    void users() {
        var a=auth.loginUser(new WechatIdentity("messaging-http-it",UUID.randomUUID().toString()));
        var b=auth.loginUser(new WechatIdentity("messaging-http-it",UUID.randomUUID().toString()));
        var o=auth.loginUser(new WechatIdentity("messaging-http-it",UUID.randomUUID().toString()));
        alice=a.me().id(); bob=b.me().id(); outsider=o.me().id();
        aToken=a.accessToken(); bToken=b.accessToken(); oToken=o.accessToken();
    }

    @Test
    void opensOneConversationForBothDirectionsAndNeverCreatesSyntheticMessages() throws Exception {
        String id=open(aToken,bob);
        assertEquals(id,open(bToken,alice));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM xl_conversation_member WHERE conversation_id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_message WHERE conversation_id=?",Integer.class,id));
        mvc.perform(get("/api/v1/conversations").header("Authorization","Bearer "+aToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].peer.id").value(bob))
                .andExpect(jsonPath("$.data.items[0].lastMessage").isEmpty());
        mvc.perform(get("/api/v1/conversations/"+id).header("Authorization","Bearer "+oToken))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/conversations").header("Authorization","Bearer "+aToken)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("peerUserId",alice))))
                .andExpect(status().isConflict());
    }

    @Test
    void persistsIdempotentlyAndSearchCannotRevealHiddenTextOrCrossConversation() throws Exception {
        String id=open(aToken,bob), other=open(aToken,outsider);
        var first=send(id,aToken,"正文 alpha","same-key");
        assertEquals("1",first.get("sequence")); assertEquals("sent",first.get("persistenceStatus"));
        assertEquals(first.get("id"),send(id,aToken,"正文 alpha","same-key").get("id"));
        postJson("/api/v1/conversations/"+id+"/messages",aToken,Map.of("text","不同内容","clientMessageId","same-key"),409);
        postJson("/api/v1/conversations/"+other+"/messages",aToken,Map.of("text","正文 alpha","clientMessageId","same-key"),409);
        var hidden=send(id,bToken,"秘密 alpha","hidden-key");
        jdbc.update("UPDATE xl_message SET moderation_state='hidden' WHERE id=?",hidden.get("id"));
        mvc.perform(get("/api/v1/conversations/"+id+"/messages").header("Authorization","Bearer "+aToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[1].visibility").value("hidden"))
                .andExpect(jsonPath("$.data.items[1].text").isEmpty()).andExpect(jsonPath("$.data.items[1].senderId").isEmpty())
                .andExpect(jsonPath("$.data.items[1].clientMessageId").isEmpty()).andExpect(jsonPath("$.data.items[1].isMine").value(false));
        mvc.perform(get("/api/v1/conversations/"+id+"/messages").param("q","alpha").header("Authorization","Bearer "+aToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/v1/conversations/"+id+"/messages").header("Authorization","Bearer "+oToken))
                .andExpect(status().isNotFound());
        postJson("/api/v1/conversations/"+id+"/messages",oToken,Map.of("text","不可越权","clientMessageId","outsider"),404);
    }

    @Test
    void historyUsesAscendingDecimalSequencesForBothDirectionsAndAround() throws Exception {
        String id=open(aToken,bob); List<Map<String,Object>> messages=new ArrayList<>();
        for(int i=1;i<=7;i++)messages.add(send(id,aToken,"消息"+i,"sequence-"+i));
        mvc.perform(get("/api/v1/conversations/"+id+"/messages").param("size","2").header("Authorization","Bearer "+bToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].sequence").value("6"))
                .andExpect(jsonPath("$.data.nextBeforeSequence").value("6")).andExpect(jsonPath("$.data.hasMore").value(true));
        mvc.perform(get("/api/v1/conversations/"+id+"/messages").param("afterSequence","2").param("size","2").header("Authorization","Bearer "+bToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].sequence").value("3"))
                .andExpect(jsonPath("$.data.items[1].sequence").value("4")).andExpect(jsonPath("$.data.nextAfterSequence").value("4"));
        mvc.perform(get("/api/v1/conversations/"+id+"/messages").param("aroundMessageId",messages.get(3).get("id").toString())
                .param("size","4").header("Authorization","Bearer "+bToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].sequence").value("2"))
                .andExpect(jsonPath("$.data.items[4].sequence").value("6"));
        mvc.perform(get("/api/v1/conversations/"+id+"/messages").param("beforeSequence","01").header("Authorization","Bearer "+bToken))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/conversations/"+id+"/messages").param("afterSequence","0").param("beforeSequence","2").header("Authorization","Bearer "+bToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void readingAndClearingAreMonotonicAndAffectOnlyCurrentMember() throws Exception {
        String id=open(aToken,bob); send(id,aToken,"第一条","a"); send(id,aToken,"第二条","b");
        mvc.perform(get("/api/v1/conversations/"+id).header("Authorization","Bearer "+bToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.unreadCount").value(2));
        putJson("/api/v1/conversations/"+id+"/read",bToken,Map.of("readUpToSequence","1"),200);
        var read=putJson("/api/v1/conversations/"+id+"/read",bToken,Map.of("readUpToSequence","0"),200);
        assertEquals("1",read.get("readThroughSequence")); assertEquals(1,read.get("unreadCount"));
        putJson("/api/v1/conversations/"+id+"/read",bToken,Map.of("readUpToSequence","3"),400);
        postJson("/api/v1/conversations/"+id+"/clear",bToken,Map.of("clearThroughSequence","1","confirmed",true),200);
        postJson("/api/v1/conversations/"+id+"/clear",bToken,Map.of("clearThroughSequence","0","confirmed",true),400);
        mvc.perform(get("/api/v1/conversations/"+id+"/messages").header("Authorization","Bearer "+bToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(get("/api/v1/conversations/"+id+"/messages").header("Authorization","Bearer "+aToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(2));
        postJson("/api/v1/me/conversations/read",bToken,Map.of(),200);
        assertEquals("2",jdbc.queryForObject("SELECT read_through_seq FROM xl_conversation_member WHERE conversation_id=? AND user_id=?",String.class,id,bob));
    }

    @Test
    void settingsArePerMemberVersionedAndBlockOnlyOwnersNewSends() throws Exception {
        String id=open(aToken,bob);
        mvc.perform(patch("/api/v1/conversations/"+id+"/settings").header("Authorization","Bearer "+aToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true,\"muted\":true,\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(patch("/api/v1/conversations/"+id+"/settings").header("Authorization","Bearer "+aToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"muted\":false,\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/conversations/"+id+"/settings").header("Authorization","Bearer "+bToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.pinned").value(false));
        jdbc.update("INSERT INTO xl_user_block(owner_id,blocked_user_id) VALUES(?,?)",alice,bob);
        assertEquals(id,open(aToken,bob));
        postJson("/api/v1/conversations/"+id+"/messages",aToken,Map.of("text","拒绝发送","clientMessageId","blocked"),403);
        send(id,bToken,"对方仍可发","not-blocked");
    }

    @Test
    @SuppressWarnings("unchecked")
    void conversationPaginationHonorsPinnedOrderAndRejectsOtherViewersCursor() throws Exception {
        String first=open(aToken,bob);
        send(first,aToken,"最近活动","recent");
        jdbc.update("UPDATE xl_conversation SET last_message_at='2000-01-01 00:00:00.000' WHERE id=?",first);
        String pinned=open(aToken,outsider);
        mvc.perform(get("/api/v1/conversations").param("size","1").header("Authorization","Bearer "+aToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(first));
        mvc.perform(patch("/api/v1/conversations/"+pinned+"/settings").header("Authorization","Bearer "+aToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"pinned\":true,\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        String response=mvc.perform(get("/api/v1/conversations").param("size","1").header("Authorization","Bearer "+aToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(pinned)).andReturn().getResponse().getContentAsString();
        String cursor=((Map<String,Object>)json.readValue(response,Map.class).get("data")).get("nextCursor").toString();
        mvc.perform(get("/api/v1/conversations").param("cursor",cursor).param("size","1").header("Authorization","Bearer "+aToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").value(first));
        mvc.perform(get("/api/v1/conversations").param("cursor",cursor).header("Authorization","Bearer "+bToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CURSOR_INVALID"));
    }

    @Test
    void sequencesRemainExactBeyondSignedLongAndRejectNonCanonicalOrOverflowWatermarks() throws Exception {
        String id=open(aToken,bob);
        jdbc.update("UPDATE xl_conversation SET next_sequence=9223372036854775808 WHERE id=?",id);
        assertEquals("9223372036854775808",send(id,aToken,"大序号","big").get("sequence"));
        var read=putJson("/api/v1/conversations/"+id+"/read",bToken,Map.of("readUpToSequence","9223372036854775808"),200);
        assertEquals("9223372036854775808",read.get("readThroughSequence"));
        for(String bad:List.of("-1","01","1.0","18446744073709551616"))
            putJson("/api/v1/conversations/"+id+"/read",bToken,Map.of("readUpToSequence",bad),400);
        putJson("/api/v1/conversations/"+id+"/read",bToken,Map.of("readUpToSequence",1),400);
    }

    String open(String token,String peer) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>(Map.of("peerUserId",peer));
        return postJson("/api/v1/conversations",token,body,201).get("id").toString();
    }
    Map<String,Object> send(String id,String token,String text,String key) throws Exception {
        Map<String,Object> body=new LinkedHashMap<>(Map.of("text",text,"clientMessageId",key));
        return postJson("/api/v1/conversations/"+id+"/messages",token,body,201);
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> postJson(String path,String token,Map<String,Object> body,int status) throws Exception {
        String value=mvc.perform(post(path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return (Map<String,Object>)json.readValue(value,Map.class).get("data");
    }
    @SuppressWarnings("unchecked")
    Map<String,Object> putJson(String path,String token,Map<String,Object> body,int status) throws Exception {
        String value=mvc.perform(put(path).header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andExpect(status().is(status)).andReturn().getResponse().getContentAsString();
        return (Map<String,Object>)json.readValue(value,Map.class).get("data");
    }
}
