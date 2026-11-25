package cn.xinkelink.ai;
import cn.xinkelink.common.api.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import java.util.regex.Pattern;
/** Only these fields can cross the model/tool boundary. No SQL or identity. */
public record AiRoute(String intent,String query,Integer days,String category,int resultIndex) {
 private static final Pattern POST_LOOKUP=Pattern.compile("^(?:(?:请|请帮我|帮我|麻烦)?(?:查|搜|找|看)|最近|近|过去).*(?:帖子|贴文).*$");
 private static final Pattern FOLLOW_UP=Pattern.compile("^(?:只|再|继续|接着)|(?:这些|那些|上面|前面|刚才|上次|上述|其中).{0,4}(?:帖|结果)|第[一二三四五六七八九十两百0-9]+[条个篇]");
 private static final Map<String,List<String>> CATEGORY_LABELS=Map.of(
  "campus",List.of("校园生活"), "lost_found",List.of("失物","招领","认领","丢失"),
  "study",List.of("学习区","学习板块","学习专区","学习分类","学习类帖子"),
  "help",List.of("互帮互助","求助区","求助帖"), "secondhand",List.of("二手","闲置","转卖"),
  "complain",List.of("吐槽"), "buddy",List.of("找搭子","搭子区"));

 static boolean startsNewPostLookup(String question){
  String text=question.strip();
  return POST_LOOKUP.matcher(text).matches()&&!FOLLOW_UP.matcher(text).find();
 }

 AiRoute withRequestedCategory(String question,Object previousCategory){
  if(!intent.equals("posts")||resultIndex>0||category==null||category.equals(previousCategory)
    ||CATEGORY_LABELS.getOrDefault(category,List.of()).stream().anyMatch(question::contains))return this;
  if(query.isBlank()&&days==null)return new AiRoute("clarify","",null,null,0);
  return new AiRoute(intent,query,days,null,resultIndex);
 }

 public static AiRoute parse(String raw,int max){
  try {
   if(raw==null||raw.length()>max)throw new IllegalArgumentException();
   var n=JsonMapper.builder().build().readTree(raw);
   if(!n.isObject())throw new IllegalArgumentException();
   n.propertyNames().forEach(k->{if(!Set.of("intent","query","days","category","resultIndex").contains(k))throw new IllegalArgumentException();});
   if(!n.path("intent").isString()||n.hasNonNull("query")&&!n.path("query").isString())throw new IllegalArgumentException();
   String intent=n.path("intent").asText(),query=n.hasNonNull("query")?n.path("query").asText():"";
   boolean posts=intent.equals("posts");
   if(!Set.of("knowledge","posts","clarify","mixed","out_of_scope").contains(intent)||query.length()>(posts?60:200))throw new IllegalArgumentException();
   Integer days=n.hasNonNull("days")?n.get("days").intValue():null;
   if(days!=null&&(!n.get("days").isIntegralNumber()||!n.get("days").canConvertToInt()||days<1||days>3650))throw new IllegalArgumentException();
   String category=n.hasNonNull("category")?n.get("category").asText():null;
   if(category!=null&&!Set.of("campus","lost_found","study","help","secondhand","complain","buddy").contains(category))throw new IllegalArgumentException();
   int index=n.path("resultIndex").asInt(0);
   if(index<0||index>20||n.hasNonNull("resultIndex")&&(!n.get("resultIndex").isIntegralNumber()||!n.get("resultIndex").canConvertToInt()))throw new IllegalArgumentException();
   if(query.isBlank()&&(intent.equals("knowledge")||posts&&days==null&&category==null&&index==0))throw new IllegalArgumentException();
   return new AiRoute(intent,query,days,category,index);
  }catch(Exception e){throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);}
 }
}
