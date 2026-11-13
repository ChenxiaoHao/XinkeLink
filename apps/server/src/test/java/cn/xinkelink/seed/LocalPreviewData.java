package cn.xinkelink.seed;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;

/** 小规模本地预览，使用AI照片；不进入生产Jar，不生成登录凭据。 */
final class LocalPreviewData {
    static final String NAMESPACE="local-preview-v1";
    private final JdbcTemplate jdbc;
    private final Path directory;
    private final String ns,previous;
    private final Instant now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    private final List<Long> users=new ArrayList<>(),posts=new ArrayList<>();
    private int inserted;
    private final Map<String,Integer> archived=new LinkedHashMap<>();
    private LocalPreviewData(Connection connection,Path directory,String ns,String previous)throws SQLException{
        if(connection.getAutoCommit())throw new IllegalArgumentException("预览生成必须使用事务");
        if(!ns.equals(NAMESPACE)&&!ns.matches("preview-it-[a-f0-9]{12}"))throw new IllegalArgumentException("预览命名空间无效");
        if(!previous.equals(LocalSeedData.NAMESPACE)&&!previous.matches("bulk-it-[a-f0-9]{12}"))throw new IllegalArgumentException("旧批次命名空间无效");
        jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));this.directory=directory;this.ns=ns;this.previous=previous;
    }
    static Map<String,Integer> plan(){return Map.of("users",4,"posts",4,"comments",8,"relations",12,"announcements",1);}
    static Map<String,Object> populate(Connection connection,Path images,String ns,String previous)throws Exception{
        var data=new LocalPreviewData(connection,images,ns,previous);data.create();
        String legacy=ns.equals(NAMESPACE)?"day5-test-adapter":"legacy-it-"+ns.substring("preview-it-".length());
        var extras=LocalPreviewExtras.apply(connection,images,ns,legacy,null);
        var report=data.verify();report.put("studentExtras",extras);return report;
    }
    static Map<String,Object> verify(Connection connection,Path images)throws Exception{return new LocalPreviewData(connection,images,NAMESPACE,LocalSeedData.NAMESPACE).verify();}
    private void create()throws Exception{
        String login=ns.equals(NAMESPACE)?"__local_preview_v1__":"__"+ns+"__";
        var old=one("SELECT status,password_hash FROM xl_admin WHERE login_name=?",login);
        if(old!=null){if(!"disabled".equals(old.get("status"))||!"!LOCAL_PREVIEW_DISABLED!".equals(old.get("password_hash")))throw new IllegalStateException("批次标记冲突");return;}
        for(String photo:List.of("campus-evening","library-notes","badminton"))if(!Files.isRegularFile(sources().resolve(photo+".png")))throw new IllegalStateException("缺少生成的照片素材："+photo);
        archiveBulk();
        long admin=add("xl_admin",row("login_name",login,"password_hash","!LOCAL_PREVIEW_DISABLED!","display_name","本地预览数据源","status","disabled","password_changed_at",at(0),"created_at",at(0)));
        String[] names={"林知夏","周予安","苏禾","陈同学"},majors={"软件工程","计算机科学与技术","电子信息工程","数字媒体技术"};
        for(int i=0;i<4;i++)users.add(add("xl_user",row("wechat_app_id",ns,"wechat_open_id","preview-user-"+(i+1),"nickname",names[i],"bio",new String[]{"本地预览账号 · 喜欢拍照和散步","本地预览账号 · 整理课程笔记中","本地预览账号 · 记录一点校园日常","本地预览账号 · 学习之外也要运动"}[i],"major",majors[i],"class_name","2024级"+(i+1)+"班","enrollment_year","2024","created_at",at(86400))));
        String[] texts={"今天下课路过这条路，刚好赶上夕阳。原本只想拍树叶，最后在楼下站了好一会儿。🍂","把数据结构里树和图的错题重新整理了一遍。感觉画出过程比只看答案有用，明天继续补最短路径。出图书馆时还碰上了晚霞。","昨天下课把蓝色练习本落在教室了，翻了书包才发现。要是有同学看到，麻烦留个言，我再确认教室和物品特征。","想找个周末一起打羽毛球的搭子，水平一般，主要想多动一动。有没有适合新手练习的方法？"};
        String[][] pictures={{"campus-evening"},{"library-notes","campus-evening"},{},{"badminton"}};
        for(int i=0;i<4;i++){
            long post=add("xl_post",row("author_id",users.get(i),"category",new String[]{"campus","study","lost_found","help"}[i],"content",texts[i],"is_anonymous",i==3,"anonymous_label",i==3?"匿名同学":null,"client_request_id",key("post",i+1),"request_hash",hash(key("post",i+1)),"created_at",at((i+1)*1800),"updated_at",at((i+1)*1800)));posts.add(post);
            for(int j=0;j<pictures[i].length;j++)bind(users.get(i),"post",post,j,pictures[i][j],(i+1)*1800L);
        }
        int[] postIndexes={0,0,1,1,1,1,2,2},authorIndexes={1,0,2,1,0,3,0,2},replyIndexes={-1,0,-1,2,2,-1,-1,6};
        String[] comments={"这会儿的光线太好了，树叶颜色也刚刚好。","真的，手机拍出来还没有现场好看。","图这一章我也在复习，最容易把遍历顺序弄混。","可以先画一个小图，手动走一遍队列或栈。","记下了，今晚准备按这个办法再做两题。","最短路径建议把几种算法适用的条件一起整理。","可以去公共失物招领点问问，再留一下物品特征。","谢谢，我下课再去找找。"};
        List<Long> ids=new ArrayList<>();
        for(int i=0;i<8;i++){Long root=replyIndexes[i]<0?null:ids.get(replyIndexes[i]);ids.add(add("xl_comment",row("target_type","post","target_id",posts.get(postIndexes[i]),"author_id",users.get(authorIndexes[i]),"root_id",root,"reply_to_id",root,"content",comments[i],"client_request_id",key("comment",i+1),"request_hash",hash(key("comment",i+1)),"created_at",at(900-i*30),"updated_at",at(900-i*30))));}
        for(int i=0;i<4;i++){
            for(int offset=1;offset<=2;offset++)add("xl_like",row("user_id",users.get((i+offset)%4),"target_type","post","target_id",posts.get(i),"created_at",at(600)));
            add("xl_favorite",row("user_id",users.get((i+1)%4),"target_type","post","target_id",posts.get(i),"created_at",at(600)));
        }
        add("xl_announcement",row("title","一起分享校园里的小事","body","这里展示的是本地演示内容，人物均为模拟，图片由AI生成，不对应真实校区。","category","community","scope","all","status","published","created_by",admin,"updated_by",admin,"client_request_id",key("announcement",1),"request_hash",hash(key("announcement",1)),"first_published_at",at(60),"last_published_at",at(60),"created_at",at(120),"updated_at",at(60),"version",1));
    }
    private void archiveBulk(){
        for(String table:List.of("xl_comment","xl_post","xl_announcement")){
            int count=jdbc.update("UPDATE "+table+" SET deleted_at=?,updated_at=?,version=version+1 WHERE client_request_id LIKE ? AND deleted_at IS NULL",at(0),at(0),previous+":%");archived.put(table,count);
        }
        archived.put("xl_user",jdbc.update("UPDATE xl_user SET deleted_at=?,updated_at=?,version=version+1 WHERE wechat_app_id=? AND deleted_at IS NULL",at(0),at(0),previous));
        int count=archived.values().stream().mapToInt(Integer::intValue).sum();
        if(count>0)add("xl_audit_log",row("operation_id",ns+":archive","actor_type","system","actor_display","本地预览数据工具","module","settings","action","archive_local_seed","target_type","seed_batch","target_count",count,"outcome","success","summary_code","local_seed_archive","result_code","OK","changed_fields","[]","created_at",at(0)));
    }
    private void bind(long owner,String type,long parent,int position,String photo,long seconds)throws Exception{
        var decoded=ImageIO.read(sources().resolve(photo+".png").toFile());if(decoded==null)throw new IllegalStateException("照片无法解码");
        var output=new ByteArrayOutputStream();ImageIO.write(decoded,"png",output);byte[] bytes=output.toByteArray();
        String storage=UUID.nameUUIDFromBytes((ns+":"+type+":"+parent+":"+position).getBytes(StandardCharsets.UTF_8))+".png";Files.createDirectories(directory);Path file=directory.resolve(storage);
        try{Files.write(file,bytes,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException e){if(!Arrays.equals(bytes,Files.readAllBytes(file)))throw new IllegalStateException("同名照片不同，未覆盖");}
        long image=add("xl_image_asset",row("owner_user_id",owner,"purpose",type,"storage_key",storage,"media_type","image/png","byte_size",bytes.length,"width",decoded.getWidth(),"height",decoded.getHeight(),"sha256",hash(bytes),"state","ready","created_at",at(seconds+60),"updated_at",at(seconds)));
        add("xl_media_ref",row("owner_type",type,"owner_id",parent,"position",position,"image_id",image,"created_at",at(seconds)));
    }
    private Map<String,Object> verify()throws Exception{
        Map<String,Integer> actual=new LinkedHashMap<>();actual.put("users",count("SELECT COUNT(*) FROM xl_user WHERE wechat_app_id=?",ns));
        for(var entry:Map.of("posts","xl_post","comments","xl_comment","announcements","xl_announcement").entrySet())actual.put(entry.getKey(),count("SELECT COUNT(*) FROM "+entry.getValue()+" WHERE client_request_id LIKE ?",ns+":%"));
        actual.put("relations",count("SELECT (SELECT COUNT(*) FROM xl_like l JOIN xl_user u ON u.id=l.user_id WHERE u.wechat_app_id=?)+(SELECT COUNT(*) FROM xl_favorite f JOIN xl_user u ON u.id=f.user_id WHERE u.wechat_app_id=?)",ns,ns));
        if(!actual.equals(plan()))throw new IllegalStateException("小规模预览数量不符");
        var assets=jdbc.queryForList("SELECT a.* FROM xl_image_asset a JOIN xl_user u ON u.id=a.owner_user_id WHERE u.wechat_app_id=?",ns);
        for(var asset:assets){byte[] bytes=Files.readAllBytes(directory.resolve(asset.get("storage_key").toString()));if(!MessageDigest.isEqual(hash(bytes),(byte[])asset.get("sha256"))||bytes.length!=((Number)asset.get("byte_size")).intValue())throw new IllegalStateException("照片资源校验失败");}
        return row("namespace",ns,"expected",plan(),"actual",actual,"inserted",inserted,"archived",archived,"boundPhotos",assets.size(),"sourcePhotos",3,"imageDirectory",directory.toString(),"validation","passed","syntheticNonLoginUsers",true);
    }
    private static Path sources(){Path root=Path.of("").toAbsolutePath().normalize();if(root.endsWith(Path.of("apps","server")))root=root.getParent().getParent();return root.resolve("docs/assets/local-preview");}
    private long add(String table,Map<String,Object> values){jdbc.update("INSERT INTO "+table+" ("+String.join(",",values.keySet())+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.values().toArray());inserted++;return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);}
    private Map<String,Object> one(String sql,Object...args){var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
    private int count(String sql,Object...args){return jdbc.queryForObject(sql,Integer.class,args);}
    private Timestamp at(long seconds){return Timestamp.from(now.minusSeconds(seconds));}
    private String key(String kind,int index){return ns+":"+kind+":"+index;}
    private static byte[] hash(String value){return hash(value.getBytes(StandardCharsets.UTF_8));}
    private static byte[] hash(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(Exception e){throw new IllegalStateException(e);}}
    private static Map<String,Object> row(Object...entries){var result=new LinkedHashMap<String,Object>();for(int i=0;i<entries.length;i+=2)result.put((String)entries[i],entries[i+1]);return result;}
}
