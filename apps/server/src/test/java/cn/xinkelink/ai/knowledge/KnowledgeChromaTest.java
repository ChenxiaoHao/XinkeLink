package cn.xinkelink.ai.knowledge;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class KnowledgeChromaTest {
 @Test void neverUsesImplicitLocalEmbeddingAndFailsWhenUnconfigured(){
  var store=new KnowledgeChroma("", "default_tenant","default_database");
  assertThrows(cn.xinkelink.common.api.BusinessException.class,()->store.verify("fake",0));
 }
}
