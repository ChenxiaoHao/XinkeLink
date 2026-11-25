package cn.xinkelink.ai.knowledge;

import cn.xinkelink.ai.AiGateway;
import cn.xinkelink.auth.*;
import cn.xinkelink.common.api.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.*;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** REAL isolated MySQL and Chroma. External models are explicitly mocked; scores are not calibration. */
@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles({"db","auth"})
class KnowledgeIT {
 @Autowired KnowledgeService knowledge;@Autowired JdbcTemplate jdbc;@Autowired AuthService auth;@Autowired PasswordEncoder passwords;@Autowired MockMvc mvc;
 @MockitoBean AiGateway gateway;@MockitoSpyBean KnowledgeChroma chroma;
 AuthPrincipal admin;String token;List<Long> created=new ArrayList<>();
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",KnowledgeIT::url);r.add("spring.flyway.url",KnowledgeIT::url);r.add("xinkelink.ai.chroma.url",()->"http://127.0.0.1:18300");}
 static String url(){String url=System.getenv("XINKELINK_DB_URL");if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:13306/xinkelink_cache_[a-zA-Z0-9_]+_test(?:\\?.*)?"))throw new IllegalStateException("Requires isolated named 13306 database");return url;}
 @BeforeEach void setup(){reset(gateway);when(gateway.setting(anyString(),anyInt())).thenAnswer(i->i.getArgument(1));when(gateway.embed(anyString())).thenReturn(new float[]{1,0,0});when(gateway.rerank(anyString(),anyList())).thenAnswer(i->Collections.nCopies(i.<List<String>>getArgument(1).size(),.9));when(gateway.chat(anyString(),anyString())).thenAnswer(i->i.<String>getArgument(0).contains("证据检查器")?"{\"status\":\"sufficient\",\"evidenceIds\":[\"1\"]}":"隔离合成样例要求提交申请表[1]");
  String name="ai-k-"+UUID.randomUUID();jdbc.update("INSERT INTO xl_admin(login_name,password_hash,display_name,password_changed_at) VALUES(?,?,'AI隔离验收',UTC_TIMESTAMP(3))",name,passwords.encode("test-pass"));var login=auth.loginAdmin(name,"test-pass");token=login.tokens().accessToken();admin=new AuthPrincipal(Long.parseLong(login.tokens().admin().id()),0,"admin");knowledge.restore();
 }
 KnowledgeService.Draft draft(String version,boolean reviewed,String until){return new KnowledgeService.Draft("隔离合成制度样例","https://hbcet.hpu.edu.cn/isolated-synthetic-test.htm",version,"2026-11-17","all","仅隔离测试，不代表真实学校制度","第一条 申请休学需要提交申请表。\n但因疾病不能办理的，可由代理人提交。",reviewed,"sample.txt",Base64.getEncoder().encodeToString("synthetic original".getBytes(java.nio.charset.StandardCharsets.UTF_8)),"",until);}
 long create(){long id=Long.parseLong((String)knowledge.create(draft("v1",true,""),admin).get("id"));created.add(id);return id;}
 @AfterEach void cleanup(){reset(chroma);for(long id:created)knowledge.disable(id,admin);}
 @Test void actualArchivePublishHybridSearchAndRestartRetainSourceVersion(){long id=create();knowledge.publish(id,admin);var first=knowledge.searchKnowledge("如何申请休学","all");assertEquals("answered",first.status());assertEquals("v1",first.sources().getFirst().get("version"));assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_ai_knowledge_version WHERE document_id=?",Integer.class,id));assertArrayEquals("synthetic original".getBytes(java.nio.charset.StandardCharsets.UTF_8),jdbc.queryForObject("SELECT original_file FROM xl_ai_knowledge_version WHERE document_id=?",byte[].class,id));knowledge.restore();assertEquals("answered",knowledge.searchKnowledge("休学申请","all").status());}
 @Test void publishedTestSourceRetainsItsOriginAndLabelsGeneratedAnswers(){
  var draft=new KnowledgeService.Draft("【测试样例】校园请假流程","urn:xinkelink:demo-knowledge","demo-v1","","all","虚构测试资料","【测试样例】申请休学需要提交申请表。",true,"","","","");
  long id=Long.parseLong((String)knowledge.create(draft,admin).get("id"));created.add(id);
  knowledge.publish(id,admin);
  when(gateway.chat(contains("每个事实段末"),anyString())).thenReturn("测试样例要求提交申请表[证据1]");
  var answer=knowledge.searchKnowledge("测试样例中的休学怎么申请？","all");
  assertEquals("answered",answer.status());
  assertEquals("urn:xinkelink:demo-knowledge",answer.sources().getFirst().get("url"));
  assertEquals("1",answer.sources().getFirst().get("citationId"));
  assertTrue(answer.text().startsWith("【测试回答】"));
  assertTrue(answer.text().endsWith("申请表[1]"));

  when(gateway.chat(contains("每个事实段末"),anyString())).thenReturn("测试样例要求提交申请表[证据9]");
  assertThrows(BusinessException.class,()->knowledge.searchKnowledge("测试样例中的休学怎么申请？","all"));
 }
 @Test void unpublishedAndUnreviewedDraftCannotAnswer(){long id=create();knowledge.update(id,draft("v1",false,""),admin);assertThrows(BusinessException.class,()->knowledge.publish(id,admin));assertEquals("no_candidates",knowledge.searchKnowledge("休学","all").status());}
 @Test void failedNewIndexKeepsEffectiveOldVersionAcrossRestart(){long id=create();knowledge.publish(id,admin);knowledge.update(id,draft("v2",true,""),admin);doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE)).when(chroma).prepare(anyList());assertThrows(BusinessException.class,()->knowledge.publish(id,admin));assertEquals("v1",knowledge.searchKnowledge("休学","all").sources().getFirst().get("version"));reset(chroma);knowledge.restore();assertEquals("v1",knowledge.searchKnowledge("休学","all").sources().getFirst().get("version"));}
 @Test void disableRemovesBothRecallPathsAndCannotReactivateOnDraftEdit(){long id=create();knowledge.publish(id,admin);knowledge.disable(id,admin);assertEquals("no_candidates",knowledge.searchKnowledge("休学","all").status());knowledge.update(id,draft("v2",true,""),admin);knowledge.restore();assertEquals("no_candidates",knowledge.searchKnowledge("休学","all").status());}
 @Test void expiredPublishedDocumentNeverReachesModel(){long id=create();knowledge.update(id,draft("old",true,"2020-01-01"),admin);knowledge.publish(id,admin);clearInvocations(gateway);assertEquals("outside_validity",knowledge.searchKnowledge("休学","all").status());verifyNoInteractions(gateway);}
 @Test void administratorAuthenticationAndAuditUseExistingBoundary()throws Exception{mvc.perform(get("/api/v1/admin/ai/knowledge")).andExpect(status().isUnauthorized());long id=create();mvc.perform(post("/api/v1/admin/ai/knowledge/"+id+"/publish").header("Authorization","Bearer "+token)).andExpect(status().isOk());assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM xl_audit_log WHERE module='ai_knowledge' AND admin_id=?",Integer.class,admin.subjectId())>0);}
}

