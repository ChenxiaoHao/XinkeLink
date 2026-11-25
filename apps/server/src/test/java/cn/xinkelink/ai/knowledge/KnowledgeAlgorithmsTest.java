package cn.xinkelink.ai.knowledge;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeAlgorithmsTest {
 @Test void chineseBm25FindsRegulationWithoutSpaces() {
  var index=new KnowledgeAlgorithms(Map.of("a","学生申请休学应当提交书面材料", "b","食堂营业时间为上午七点"));
  assertEquals(List.of("a"),index.search("如何申请休学",30));
 }
 @Test void rrfUsesRanksAndDeduplicates() {
  assertEquals(List.of("b","a","c"),KnowledgeAlgorithms.rrf(List.of("a","b"),List.of("b","c"),20));
 }
 @Test void citationsRejectFabricatedAndMissingSources() {
  assertFalse(KnowledgeAlgorithms.validCitations("答案[9]",Set.of("1")));
  assertFalse(KnowledgeAlgorithms.validCitations("没有引文",Set.of("1")));
  assertTrue(KnowledgeAlgorithms.validCitations("规定[1]",Set.of("1")));
 }
 @Test void chunkingKeepsShortExceptionParagraphWithClause() {
  var chunks=KnowledgeAlgorithms.chunks("第一条 学生申请应提交申请表。\n但因疾病不能申请的，可由代理人提交。\n第二条 审核由学院负责。",1200);
  assertEquals(2,chunks.size());assertTrue(chunks.getFirst().contains("代理人"));
 }
 @Test void pageBoundaryCannotDetachAnExceptionFromItsClause(){
  var chunks=KnowledgeAlgorithms.chunks("【页码 1】\n第一条 申请应由本人提交。\n【页码 2】\n但因疾病不能办理的可以由代理人提交。\n第二条 审核由学院负责。",1200);
  assertTrue(chunks.stream().anyMatch(c->c.contains("申请应由本人提交")&&c.contains("代理人提交")));
 }
 @Test void importUrlRejectsPrivateOrForeignTargets() {
  assertThrows(RuntimeException.class,()->KnowledgeImport.validateUrl("http://127.0.0.1/a"));
  assertThrows(RuntimeException.class,()->KnowledgeImport.validateUrl("https://hbcet.hpu.edu.cn.evil.test/a"));
  assertThrows(RuntimeException.class,()->KnowledgeImport.validateUrl("https://hbcet.hpu.edu.cn:8443/a"));
  assertDoesNotThrow(()->KnowledgeImport.validateUrl("https://hbcet.hpu.edu.cn/info/1.htm"));
 }
}
