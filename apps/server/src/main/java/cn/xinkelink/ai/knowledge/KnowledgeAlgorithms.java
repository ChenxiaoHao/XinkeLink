package cn.xinkelink.ai.knowledge;

import java.util.*;
import java.util.regex.Pattern;

/** Bounded Chinese character bigrams plus Latin words; no whitespace-only tokenization. */
final class KnowledgeAlgorithms {
 private final Map<String,Map<String,Integer>> docs=new LinkedHashMap<>();
 private final Map<String,Integer> df=new HashMap<>();
 private final double average;
 KnowledgeAlgorithms(Map<String,String> texts){
  texts.forEach((id,text)->{var bag=bag(text);docs.put(id,bag);bag.keySet().forEach(t->df.merge(t,1,Integer::sum));});
  average=docs.values().stream().mapToInt(b->b.values().stream().mapToInt(Integer::intValue).sum()).average().orElse(1);
 }
 static Map<String,Integer> bag(String value){
  Map<String,Integer> bag=new HashMap<>();
  var m=Pattern.compile("[\\p{IsHan}]+|[a-zA-Z0-9]+").matcher(value.toLowerCase(Locale.ROOT));
  while(m.find()) {String part=m.group();if(Character.UnicodeScript.of(part.codePointAt(0))==Character.UnicodeScript.HAN){
    int[] cps=part.codePoints().toArray();
    if(cps.length==1)bag.merge(part,1,Integer::sum);
    for(int i=0;i<cps.length-1;i++)bag.merge(new String(cps,i,2),1,Integer::sum);
   }else bag.merge(part,1,Integer::sum);
  }return bag;
 }
 List<String> search(String query,int limit){
  var terms=bag(query).keySet();Map<String,Double> scores=new LinkedHashMap<>();
  docs.forEach((id,bag)->{double length=bag.values().stream().mapToInt(Integer::intValue).sum(),score=0;
   for(String t:terms){double tf=bag.getOrDefault(t,0);if(tf==0)continue;
    double frequency=df.getOrDefault(t,0);double idf=Math.log(1+(docs.size()-frequency+.5)/(frequency+.5));
    score+=idf*(tf*2.2)/(tf+1.2*(.25+.75*length/Math.max(1,average)));
   }if(score>0)scores.put(id,score);
  });return scores.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed()).limit(limit).map(Map.Entry::getKey).toList();
 }
 static List<String> rrf(List<String> a,List<String> b,int n){
  Map<String,Double> scores=new LinkedHashMap<>();
  for(var list:List.of(a,b)){Set<String> seen=new HashSet<>();for(int i=0;i<list.size();i++)if(seen.add(list.get(i)))scores.merge(list.get(i),1d/(61+i),Double::sum);}
  return scores.entrySet().stream().sorted(Map.Entry.<String,Double>comparingByValue().reversed()).limit(n).map(Map.Entry::getKey).toList();
 }
 static boolean validCitations(String text,Set<String> ids){
  var matcher=Pattern.compile("\\[([^\\]]+)\\]").matcher(text);boolean found=false;
  while(matcher.find()){if(!ids.contains(matcher.group(1)))return false;found=true;}return found;
 }
 static List<String> chunks(String body,int max){
  List<String> result=new ArrayList<>();StringBuilder current=new StringBuilder();
  for(String paragraph:body.replace("\r", "").split("\n")){
   if(paragraph.isBlank())continue;
   boolean heading=paragraph.matches("^(第[一二三四五六七八九十百零0-9]+[条章节]|#{1,6} ).*");
   if(heading&&!current.isEmpty()){result.add(current.toString());current.setLength(0);}
   // Never sever an individual clause/exception to meet a cosmetic chunk target.
   if(paragraph.length()>16000)throw new IllegalArgumentException("Clause exceeds safe bound");
   if(current.length()+paragraph.length()>16000)throw new IllegalArgumentException("Clause exceeds safe bound");
   current.append(paragraph).append('\n');
   if(current.length()>max && heading){/* next clause closes this chunk */}
  }
  if(!current.isEmpty())result.add(current.toString());
  return List.copyOf(result);
 }
}
