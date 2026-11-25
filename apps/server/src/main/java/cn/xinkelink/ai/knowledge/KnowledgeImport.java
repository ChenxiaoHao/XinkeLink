package cn.xinkelink.ai.knowledge;

import cn.xinkelink.common.api.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

@Component @Profile("auth")
public class KnowledgeImport {
 static final int MAX=5*1024*1024;
 private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
 static void validateUrl(String url){
  try {URI u=URI.create(url);
   if(!"https".equalsIgnoreCase(u.getScheme())||!"hbcet.hpu.edu.cn".equalsIgnoreCase(u.getHost())||u.getUserInfo()!=null||(u.getPort()!=-1&&u.getPort()!=443)||u.getFragment()!=null)throw invalid();
  }catch(IllegalArgumentException ex){throw invalid();}
 }
 private static InetAddress validateDns(String url)throws IOException{
  InetAddress[] addresses=InetAddress.getAllByName(URI.create(url).getHost());
  for(InetAddress ip:addresses){
   byte[] a=ip.getAddress();
   if(ip.isAnyLocalAddress()||ip.isLoopbackAddress()||ip.isLinkLocalAddress()||ip.isSiteLocalAddress()||ip.isMulticastAddress()||(a.length==16&&(a[0]&0xfe)==0xfc)||(a.length==4&&((a[0]&255)==0||(a[0]&255)==100&&(a[1]&192)==64||(a[0]&255)>=224)))throw invalid();
  }return addresses[0];
 }
 public Map<String,Object> extract(MultipartFile file){
  if(file==null||file.isEmpty()||file.getSize()>MAX)throw invalid();
  try{return extract(file.getOriginalFilename(),file.getBytes());}catch(IOException ex){throw invalid();}
 }
 Map<String,Object> extract(String filename,byte[] bytes){
  if(bytes.length==0||bytes.length>MAX)throw invalid();
  String name=filename==null?"":filename.toLowerCase(Locale.ROOT);String body;
  List<String> warnings=new ArrayList<>();
  try {
   if(name.endsWith(".pdf")){
    try(var pdf=Loader.loadPDF(bytes)){
     if(pdf.getNumberOfPages()>400)throw invalid();
     PDFTextStripper stripper=new PDFTextStripper();var out=new BoundedTextWriter(200000,System.nanoTime()+Duration.ofSeconds(20).toNanos());
     for(int i=1;i<=pdf.getNumberOfPages();i++){out.write("【页码 "+i+"】\n");stripper.setStartPage(i);stripper.setEndPage(i);stripper.writeText(pdf,out);out.write("\n");}
     body=out.toString();warnings.add("仅提取PDF文字；图表及附件请人工核对。扫描件需提供文字版或人工校对文本。");
     if(body.replaceAll("【页码 \\d+】", "").isBlank())return empty(filename,bytes,"PDF没有可提取正文，请上传文字版或人工填写校对正文。");
    }
   }else if(name.endsWith(".docx")){
    byte[] xml=null;int expanded=0;int entryCount=0;
    try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){if(++entryCount>1000)throw invalid();byte[] content=zip.readNBytes(MAX+1);expanded+=content.length;if(content.length>MAX||expanded>MAX*4)throw invalid();if(entry.getName().equals("word/document.xml"))xml=content;}}
    if(xml==null)throw invalid();var factory=DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");factory.setNamespaceAware(true);
    var builder=factory.newDocumentBuilder();builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){@Override public void fatalError(org.xml.sax.SAXParseException failure)throws org.xml.sax.SAXException{throw failure;}});var doc=builder.parse(new ByteArrayInputStream(xml));var paragraphs=doc.getElementsByTagNameNS("http://schemas.openxmlformats.org/wordprocessingml/2006/main","p");StringBuilder out=new StringBuilder();for(int i=0;i<paragraphs.getLength();i++)out.append(paragraphs.item(i).getTextContent()).append('\n');body=out.toString();warnings.add("DOCX图片、页码、脚注及附件未提取，请核对原件并在正文补充必要上下文。");
   }else if(name.endsWith(".txt")||name.endsWith(".md"))body=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
   else throw invalid();
  }catch(BusinessException ex){throw ex;}catch(Exception ex){throw invalid();}
  if(body.isBlank())return empty(filename,bytes,"没有可提取正文，请提供文字版或手工校对文本。");
  if(body.length()>200000)throw invalid();
  return result(body,filename,bytes,warnings);
 }
 public Map<String,Object> importUrl(String input){
  String url=input;long deadline=System.nanoTime()+Duration.ofSeconds(20).toNanos();
  try {for(int redirects=0;redirects<=3;redirects++){
    validateUrl(url);InetAddress address=validateDns(url);if(System.nanoTime()>deadline)throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
    // Pin the validated address for the actual TLS connection to prevent DNS rebinding.
    var connection=(javax.net.ssl.HttpsURLConnection)URI.create(url).toURL().openConnection(Proxy.NO_PROXY);
    connection.setSSLSocketFactory(new PinnedTls(address,URI.create(url).getHost()));
    connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(5000);connection.setReadTimeout(8000);connection.setRequestProperty("Accept","text/html");
    try {
     int status=connection.getResponseCode();
     if(status>=300&&status<400){String location=connection.getHeaderField("Location");if(location==null)throw invalid();url=URI.create(url).resolve(location).toString();continue;}
     if(status!=200)throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
     if(!Optional.ofNullable(connection.getContentType()).orElse("").toLowerCase(Locale.ROOT).contains("text/html"))throw invalid();
     if(connection.getContentLengthLong()>MAX)throw invalid();byte[] bytes;
     try(var stream=connection.getInputStream();var output=new ByteArrayOutputStream()){
      byte[] buffer=new byte[8192];int size;while((size=stream.read(buffer))!=-1){if(output.size()+size>MAX)throw invalid();if(System.nanoTime()>deadline)throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);output.write(buffer,0,size);}bytes=output.toByteArray();
     }
     var doc=Jsoup.parse(new ByteArrayInputStream(bytes),null,url);doc.select("script,style,noscript,nav,header,footer").remove();
     var article=doc.selectFirst(".v_news_content,article,.article-content,.content");String body=(article==null?doc.body():article).wholeText();if(body.isBlank()||body.length()>200000)throw invalid();
     var result=result(body,"article.html",bytes,List.of("网页图片和附件未导入，请核对重要信息后发布；本次不会追踪附件或其他链接。"));result.put("source",url);result.put("title",doc.title());return result;
    }finally{connection.disconnect();}
   }throw invalid();
  }catch(BusinessException ex){throw ex;}catch(Exception ex){throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);}
 }
 /** Bound aggregate output before appending, including page markers; never buffer a whole page via getText. */
 private static final class BoundedTextWriter extends Writer {
  private final StringBuilder text=new StringBuilder();private final int maximum;private final long deadline;
  BoundedTextWriter(int maximum,long deadline){this.maximum=maximum;this.deadline=deadline;}
  private void check(int length){if(length>maximum-text.length()||System.nanoTime()>deadline)throw invalid();}
  @Override public void write(char[] chars,int offset,int length){Objects.checkFromIndexSize(offset,length,chars.length);check(length);text.append(chars,offset,length);}
  @Override public void write(String value,int offset,int length){Objects.checkFromIndexSize(offset,length,value.length());check(length);text.append(value,offset,offset+length);}
  @Override public void flush(){}
  @Override public void close(){}
  @Override public String toString(){return text.toString();}
 }
 private static final class PinnedTls extends javax.net.ssl.SSLSocketFactory {
  private final InetAddress address;private final String hostname;private final javax.net.ssl.SSLSocketFactory delegate=(javax.net.ssl.SSLSocketFactory)javax.net.ssl.SSLSocketFactory.getDefault();
  PinnedTls(InetAddress address,String hostname){this.address=address;this.hostname=hostname;}
  private Socket connect()throws IOException{Socket raw=new Socket();try{raw.connect(new InetSocketAddress(address,443),5000);raw.setSoTimeout(8000);return delegate.createSocket(raw,hostname,443,true);}catch(IOException ex){raw.close();throw ex;}}
  @Override public String[] getDefaultCipherSuites(){return delegate.getDefaultCipherSuites();}
  @Override public String[] getSupportedCipherSuites(){return delegate.getSupportedCipherSuites();}
  @Override public Socket createSocket()throws IOException{return new Socket(){@Override public void connect(SocketAddress endpoint,int timeout)throws IOException{super.connect(new InetSocketAddress(address,443),timeout>0?Math.min(timeout,5000):5000);}};}
  @Override public Socket createSocket(Socket socket,String host,int port,boolean close)throws IOException{return delegate.createSocket(socket,hostname,443,close);}
  @Override public Socket createSocket(String host,int port)throws IOException{return connect();}
  @Override public Socket createSocket(String host,int port,InetAddress local,int localPort)throws IOException{return connect();}
  @Override public Socket createSocket(InetAddress host,int port)throws IOException{return connect();}
  @Override public Socket createSocket(InetAddress host,int port,InetAddress local,int localPort)throws IOException{return connect();}
 }
 private static Map<String,Object> empty(String name,byte[] bytes,String warning){return result("",name,bytes,List.of(warning));}
 private static Map<String,Object> result(String body,String name,byte[] bytes,List<String> warnings){Map<String,Object> m=new LinkedHashMap<>();m.put("body",body);m.put("filename",name);m.put("warnings",warnings);m.put("originalBase64",Base64.getEncoder().encodeToString(bytes));return m;}
 private static BusinessException invalid(){return new BusinessException(ErrorCode.VALIDATION_FAILED);}
}

