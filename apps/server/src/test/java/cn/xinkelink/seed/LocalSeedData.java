package cn.xinkelink.seed;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.List;
import java.util.*;

/** 仅测试classpath可用，不会打入生产Jar。 */
public final class LocalSeedData {
    public static final String NAMESPACE="local-seed-v1";
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final Path images;
    private final String namespace;
    private final Map<String,Integer> inserted=new TreeMap<>();
    private final List<Map<String,Object>> users=new ArrayList<>(),posts=new ArrayList<>(),comments=new ArrayList<>(),messages=new ArrayList<>(),reports=new ArrayList<>(),announcements=new ArrayList<>();
    private final List<Reaction> reactions=new ArrayList<>();
    private Instant anchor;
    private long admin;
    private int mediaCount,filesCreated;
    private LocalSeedData(Connection connection,Path images,String namespace)throws Exception{
        if(connection.getAutoCommit())throw new IllegalArgumentException("数据生成必须由调用方事务包裹");
        if(!namespace.equals(NAMESPACE)&&!namespace.matches("seed-it-[a-f0-9]{12}"))throw new IllegalArgumentException("未知种子命名空间");
        this.jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));this.images=images.toAbsolutePath().normalize();this.namespace=namespace;
    }
    public static Map<String,Integer> plan(){return Map.of("users",100,"posts",500,"comments",2000,"relations",2400,"conversations",150,"messages",1000,"reports",48,"notifications",472,"announcements",15);}
    public static void checkDatabase(String url){
        if(url==null||!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost|\\[::1\\])(?::[0-9]{1,5})?/[a-zA-Z0-9_]+_(dev|test)(\\?.*)?"))throw new IllegalArgumentException("仅允许本机dev/test数据库");
    }
    static Map<String,Object> populate(Connection connection,Path images,String namespace)throws Exception{
        var seed=new LocalSeedData(connection,images,namespace);seed.create();return seed.verify();
    }
    static Map<String,Object> verify(Connection connection,Path images,String namespace)throws Exception{return new LocalSeedData(connection,images,namespace).verify();}

    private void create()throws Exception{
        String login=namespace.equals(NAMESPACE)?"__local_seed_v1__":"__"+namespace+"__";
        var existing=one("SELECT * FROM xl_admin WHERE login_name=?",login);
        if(existing!=null&&(!"disabled".equals(existing.get("status"))||!"!LOCAL_SEED_DISABLED!".equals(existing.get("password_hash"))))throw new IllegalStateException("保留种子账号发生冲突，未覆盖");
        // 批次完整提交后只做核验，不重新补入用户后来移除的图片或互动关系。
        if(existing!=null)return;
        anchor=existing==null?Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS):time(existing,"created_at");
        admin=id(entity("xl_admin",row("login_name",login,"password_hash","!LOCAL_SEED_DISABLED!","display_name","本地虚构数据源","status","disabled","password_changed_at",at(0),"created_at",at(0),"updated_at",at(0)),"login_name=?",login).row());
        createUsers();createPosts();createComments();createReactions();createConversations();createReports();createAnnouncements();createNotices();
    }
    private void createUsers()throws Exception{
        String[] names={"林沐","陈禾","周星","许言","方宁","江夏","叶然","苏青","顾远","唐宁","沈舟","温书","陆晴","何川","宋棠","吴桐","徐墨","赵晨","程溪","秦越"};
        String[] majors={"计算机科学与技术","软件工程","电子信息工程","数据科学与大数据技术","数字媒体技术"};
        for(int i=1;i<=100;i++){
            boolean temporary=i>=95&&i<=97,permanent=i>=98;
            String year=Integer.toString(2022+(i-1)%5);boolean reportRestricted=i>=91&&i<=94;
            var value=entity("xl_user",row("wechat_app_id",namespace,"wechat_open_id",String.format("local-user-%03d",i),"nickname","虚构·"+names[(i-1)%20]+((i-1)/20+1),"bio",new String[]{"虚构资料：喜欢阅读，正在整理课程笔记。","虚构资料：课后散步，也会参加志愿活动。","虚构资料：关注科技与阅读，慢慢学新技能。","虚构资料：记录校园日常，不对应真实学生。"}[(i-1)%4],"major",majors[(i-1)%5],"class_name",year+"级"+((i-1)%4+1)+"班","enrollment_year",year,"ban_state",permanent?"permanent":temporary?"temporary":"none","ban_until",temporary?at(-4*86400):null,"restrict_publish",reportRestricted||i==87||i==90,"restrict_comment",reportRestricted||i==88||i==90,"restrict_message",reportRestricted||i==89,"last_active_at",at(i>=87?12*86400:(i%7)*86400+3600),"created_at",at(150*86400+i*60),"updated_at",at(reportRestricted?4*3600:i>=95?3*86400:i>=87?0:86400),"version",i>=87?1:0),"wechat_app_id=? AND wechat_open_id=?",namespace,String.format("local-user-%03d",i));
            var user=value.row();users.add(user);long uid=id(user);
            long avatar=image(uid,"avatar","avatar:"+i,"虚构头像",i);
            if(value.created()){
                // 仅补齐本事务刚插入的新用户；重复执行不覆盖任何现有头像或资料。
                jdbc.update("UPDATE xl_user SET avatar_image_id=? WHERE id=?",avatar,uid);user.put("avatar_image_id",avatar);
                relation("xl_media_ref",row("owner_type","user_avatar","owner_id",uid,"position",0,"image_id",avatar,"created_at",at(150*86400)));
            }
            relation("xl_user_preference",row("user_id",uid,"messages",i%7!=0,"interactions",i%9!=0,"announcements",true,"sound",i%5==0,"created_at",at(150*86400)));
            if(i>=87&&!reportRestricted)history("user",uid,uid,null,"seed_snapshot","虚构账号限制与封禁样本",row("banState","none","manualRestrictions",row("publish",false,"comment",false,"message",false),"version",0),userState(user),user.get("updated_at"),key("user-state",i));
        }
        for(int i=0;i<8;i++)relation("xl_user_block",row("owner_id",id(users.get(i)),"blocked_user_id",id(users.get((i+7)%90)),"created_at",at(3600)));
    }
    private void createPosts()throws Exception{
        String[] categories={"campus","lost_found","study","help"};
        String[] topics={"傍晚的图书馆很安静，整理了一下午课程笔记。大家复习时会怎样安排休息？","在教学楼附近发现一个蓝色水杯，已交给服务台。失主可以带物品特征前去询问。","准备把数据结构的错题重新分类，欢迎分享树与图相关的学习方法。","周末想和同学一起整理社团旧书，有没有适合分类和打包的小技巧？","校园里的银杏开始变黄了，课间散步时拍到了很舒服的光线。","有同学遗落了一把折叠雨伞，已放在公共失物招领处，请核对颜色后认领。","刚完成一份课程实验，记录了输入校验和异常处理的几个易错点。","第一次参加校园志愿活动，想请教集合前一般要准备哪些随身用品？"};
        for(int i=1;i<=500;i++){
            int authorIndex=(i-1)%100;long author=id(users.get(authorIndex));boolean anonymous=i%4==0,deleted=i>=56&&i<=65;
            long seconds=(2+i%60)*86400L;if(authorIndex>=86)seconds=Math.max(seconds,40*86400L);
            else if(i>=70&&i%10==0){long elapsed=Duration.between(anchor.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),anchor).getSeconds();seconds=Math.min(elapsed,(i%8+1)*60L);}
            else if(i>=70&&i%10==5)seconds=86400;
            Timestamp created=at(seconds),updated=later(created,at(i>=5&&i<=8?4*3600:86400));
            var post=entity("xl_post",row("author_id",author,"category",categories[(i-1)%4],"content","【本地演示·虚构内容】"+topics[(i-1)%8]+"\n样本编号："+i+"。这不是学校通知，也不对应真实失物或活动。","is_anonymous",anonymous,"anonymous_label",anonymous?"匿名同学·"+i:null,"status",i<=40?"hidden":"visible","review_state",i>=41&&i<=55?"pending":"normal","client_request_id",key("post",i),"request_hash",hash(key("post",i)),"created_at",created,"updated_at",updated,"deleted_at",deleted?at(7200):null,"version",i<=65?1:0),"author_id=? AND client_request_id=?",author,key("post",i)).row();posts.add(post);
            for(int p=0;p<new int[]{0,1,2,4}[(i-1)%4];p++){
                long image=image(author,"post","post:"+i+":"+p,"校园插图 "+(p+1),i+p+300);
                if(!deleted)relation("xl_media_ref",row("owner_type","post","owner_id",id(post),"position",p,"image_id",image,"created_at",post.get("created_at")));
            }
            if((i<=40&&!(i>=5&&i<=8))||deleted)history("post",id(post),author,null,"seed_snapshot","虚构帖子治理/删除历史",row("status","visible","deleted",false,"version",0),state("post",post),deleted?post.get("deleted_at"):post.get("updated_at"),key("post-state",i));
        }
    }
    private void createComments(){
        String[] texts={"这个建议很实用，已经记在课程笔记里了。","谢谢分享，我也准备按这个顺序试一下。","补充一个小细节：先确认时间和地点会更稳妥。","同感，慢慢积累比临时赶进度更有效。","可以先和负责同学确认，避免带错物品。","感谢提醒，已经转告一起学习的同学。"};
        int index=0;
        for(int p=0;p<500;p++){
            var post=posts.get(p);long root=0,reply=0;
            int quantity=p%10==0?0:p%10==1?8:4;
            for(int phase=0;phase<quantity;phase++){
                index++;int local=phase%4;boolean second=local==1||local==2,deleted=post.get("deleted_at")!=null||p%47==0&&local<3,reportHidden=index>=401&&index<=404,hidden=reportHidden||index%41==0;
                long author=id(users.get((p+phase+1)%86));Instant createdAt=time(post,"created_at").plusSeconds(Math.min(300+phase*60,Math.max(0,Duration.between(time(post,"created_at"),anchor).getSeconds())));Timestamp created=Timestamp.from(createdAt),updated=later(created,at(reportHidden?4*3600:86400));
                var comment=entity("xl_comment",row("target_type","post","target_id",id(post),"author_id",author,"root_id",second?root:null,"reply_to_id",second?(local==1?root:reply):null,"content","【虚构评论】"+texts[(index-1)%texts.length]+"（样本"+index+"）","status",hidden?"hidden":"visible","client_request_id",key("comment",index),"request_hash",hash(key("comment",index)),"created_at",created,"updated_at",updated,"deleted_at",deleted?later(created,at(7200)):null,"version",deleted||hidden?1:0),"author_id=? AND client_request_id=?",author,key("comment",index)).row();comments.add(comment);
                if(local==0)root=id(comment);if(local==1)reply=id(comment);
                if((deleted||hidden)&&!reportHidden)history("comment",id(comment),author,null,"seed_snapshot","虚构评论治理/删除历史",row("status","visible","deleted",false,"version",0),state("comment",comment),deleted?comment.get("deleted_at"):comment.get("updated_at"),key("comment-state",index));
            }
        }
    }
    private void createReactions(){
        relations("xl_like","post",posts.stream().filter(this::visiblePost).toList(),1200);
        relations("xl_like","comment",comments.stream().filter(this::visibleComment).toList(),600);
        relations("xl_favorite","post",posts.stream().filter(this::visiblePost).toList(),600);
    }
    private void relations(String table,String type,List<Map<String,Object>> targets,int count){
        for(int i=0;i<count;i++){
            var target=targets.get(i%targets.size());long owner=number(target,"author_id");int offset=users.indexOf(users.stream().filter(user->id(user)==owner).findFirst().orElseThrow());
            long actor=id(users.get((offset+1+i/targets.size())%90));
            Timestamp created=later(Timestamp.from(time(target,"created_at")),at(12*3600));
            relation(table,row("user_id",actor,"target_type",type,"target_id",id(target),"created_at",created));
            reactions.add(new Reaction(table,type,id(target),actor,owner,created));
        }
    }
    private void createConversations(){
        int messageIndex=0;
        String[] texts={"你好，想交流一下课程复习方法。","可以的，最近在整理课程笔记。","如果方便，可以在图书馆一起讨论。","好的，我们先确认时间。","收到，课程结束后再联系。","谢谢说明，我会再核对一下需求。","没问题，祝今天学习顺利。"};
        for(int i=0;i<150;i++){
            long a=id(users.get(i%90)),b=id(users.get((i%90+1+i/90)%90)),low=Math.min(a,b),high=Math.max(a,b);int count=i<100?7:6;
            Timestamp created=at(36*3600+i*60);var entry=entity("xl_conversation",row("low_user_id",low,"high_user_id",high,"next_sequence",count+1,"created_at",created,"updated_at",Timestamp.from(created.toInstant().plusSeconds(count*60)),"last_message_at",Timestamp.from(created.toInstant().plusSeconds(count*60))),"low_user_id=? AND high_user_id=?",low,high);long conversation=id(entry.row()),last=0;
            for(int seq=1;seq<=count;seq++){
                int index=++messageIndex;long sender=seq%2==1?high:low;Timestamp sent=Timestamp.from(created.toInstant().plusSeconds(seq*60));
                var message=entity("xl_message",row("conversation_id",conversation,"sender_id",sender,"sequence_no",seq,"client_message_id",key("message",index),"request_hash",hash(key("message",index)),"kind","text","text","【虚构私信】"+texts[(seq-1)%texts.length],"moderation_state",index>=5&&index<=8||index%211==0?"hidden":"normal","created_at",sent,"updated_at",index>=5&&index<=8?at(4*3600):sent,"version",index>=5&&index<=8||index%211==0?1:0),"sender_id=? AND client_message_id=?",sender,key("message",index)).row();messages.add(message);last=id(message);
            }
            if(entry.created())jdbc.update("UPDATE xl_conversation SET last_message_id=? WHERE id=?",last,conversation);
            for(long member:new long[]{low,high}){
                int read=member==low?count/2:i%3==0?0:count,clear=read>0&&i%5==0?1:0;
                relation("xl_conversation_member",row("conversation_id",conversation,"user_id",member,"read_through_seq",read,"cleared_through_seq",clear,"pinned",member==low&&i%7==0,"muted",i%11==0,"created_at",created,"updated_at",created));
            }
        }
    }
    private void createReports()throws Exception{
        String[] types={"post","comment","user","message"};String[] reasons={"commercial_ad","fraud_or_offsite_payment","harassment","impersonation","other"};
        for(int i=0;i<48;i++){
            String type=types[i/12];int slot=i%12;boolean processed=slot>=4,reject=slot>=8;int targetIndex;
            if(type.equals("user"))targetIndex=slot<4?40+slot:slot<8?91+slot-4:50+slot-8;
            else if(type.equals("comment"))targetIndex=slot<4?441+slot:slot<8?401+slot-4:481+slot-8;
            else if(type.equals("message"))targetIndex=slot<4?201+slot:slot<8?5+slot-4:241+slot-8;
            else targetIndex=slot<4?101+slot:slot<8?5+slot-4:111+slot-8;
            var target=switch(type){case "post"->posts.get(targetIndex-1);case "comment"->comments.get(targetIndex-1);case "user"->users.get(targetIndex-1);default->messages.get(targetIndex-1);};
            long targetUser=type.equals("user")?id(target):number(target,type.equals("message")?"sender_id":"author_id");long reporter=id(users.get((i+30)%90));
            if(type.equals("message")){var conversation=one("SELECT low_user_id,high_user_id FROM xl_conversation WHERE id=?",target.get("conversation_id"));reporter=number(conversation,"low_user_id")==targetUser?number(conversation,"high_user_id"):number(conversation,"low_user_id");}
            if(reporter==targetUser)reporter=id(users.get((i+31)%90));
            String resolution=reject?"reject":switch(type){case "post"->"hide_post";case "comment"->"hide_comment";case "user"->"restrict_user";default->"hide_message";};
            var snapshot=row("type",type,"id",Long.toString(id(target)),"title",title(target),"targetUserId",Long.toString(targetUser));
            if(target.get("content")!=null)snapshot.put("content",target.get("content"));if(type.equals("message"))snapshot.put("text",target.get("text"));
            var report=entity("xl_report",row("reporter_id",reporter,"target_type",type,"target_id",id(target),"target_user_id",targetUser,"reason_code",reasons[i%5],"description","【虚构举报】用于本地演示审核队列、证据和处理结果，不对应真实人员或违规内容。","priority",new String[]{"high","medium","low"}[i%3],"status",processed?"processed":"pending","target_snapshot",json(snapshot),"resolution",processed?resolution:null,"resolution_reason",processed?(reject?"虚构样本：核实后不予处理。":"虚构样本：已按演示规则完成处置。"):null,"resolved_by",processed?admin:null,"resolved_at",processed?at(4*3600):null,"client_request_id",key("report",i+1),"request_hash",hash(key("report",i+1)),"created_at",at(20*3600),"updated_at",at(processed?4*3600:20*3600),"version",processed?1:0),"reporter_id=? AND client_request_id=?",reporter,key("report",i+1)).row();reports.add(report);
            if(i<10){long evidence=image(reporter,"report","evidence:"+i,"虚构证据示意",i+1000);relation("xl_media_ref",row("owner_type","report_evidence","owner_id",id(report),"position",0,"image_id",evidence,"created_at",at(20*3600)));}
            if(processed){var after=type.equals("user")?userState(target):state(type,target);var before=reject?after:row("status",type.equals("message")?"normal":"visible","version",0);
                if(type.equals("user")&&!reject)before=row("banState","none","manualRestrictions",row("publish",false,"comment",false,"message",false),"version",0);
                history(type,id(target),targetUser,id(report),resolution,(String)report.get("resolution_reason"),before,after,at(4*3600),key("report-action",i+1));audit("reports",resolution,type,id(target),key("report-audit",i+1),at(4*3600));}
        }
    }
    private void createAnnouncements(){
        String[] titles={"校园阅读交流提醒","校园交流建议","图书整理志愿活动","课程复习资料整理","运动场地使用小提示","雨天出行温馨提醒","社团活动准备清单","公共学习空间礼仪","课程教材整理说明","失物招领信息说明","虚构活动已撤回通知","校园照片展示约定","周末学习互助安排","宿舍物品整理建议","学期生活记录征集"};
        for(int i=1;i<=15;i++){
            String status=i<=5?"published":i<=10?"draft":"withdrawn";boolean published=!status.equals("draft"),recent=i==1||i==11;Timestamp first=published?at(recent?2*86400:180*86400):null;
            var value=entity("xl_announcement",row("title","【本地演示】"+titles[i-1],"body","这是用于本地测试的虚构平台公告，不是学校官方通知。请通过真实渠道确认活动与服务安排。此样本用于检查公告内容、发布时间、撤回和通知关联展示。","category",new String[]{"community","activity","safety"}[(i-1)%3],"scope",new String[]{"all","forum"}[(i-1)%2],"status",status,"created_by",admin,"updated_by",admin,"client_request_id",key("announcement",i),"request_hash",hash(key("announcement",i)),"first_published_at",first,"last_published_at",first,"withdrawn_at",status.equals("withdrawn")?at(recent?86400:170*86400):null,"created_at",at(published?(recent?3*86400:190*86400):86400),"updated_at",at(status.equals("withdrawn")?(recent?86400:170*86400):published?(recent?2*86400:180*86400):86400),"version",status.equals("withdrawn")?2:published?1:0),"created_by=? AND client_request_id=?",admin,key("announcement",i)).row();announcements.add(value);
            audit("announcements","seed_snapshot","announcement",id(value),key("announcement-audit",i),value.get("updated_at"));
        }
    }
    private void createNotices(){
        for(int a:new int[]{0,10})for(int u=0;u<100;u++)notice(id(users.get(u)),"announcement:"+id(announcements.get(a))+":first_publish","announcement","announcement",null,null,id(announcements.get(a)),"announcement",id(announcements.get(a)),"平台公告","",u%3==0,at(2*86400),true);
        int index=0;
        outer:for(var comment:comments.stream().filter(this::visibleComment).toList()){
            var post=posts.stream().filter(value->id(value)==number(comment,"target_id")).findFirst().orElseThrow();long actor=number(comment,"author_id");Set<Long> recipients=new LinkedHashSet<>();
            if(comment.get("reply_to_id")!=null)recipients.add(number(comments.stream().filter(value->id(value)==number(comment,"reply_to_id")).findFirst().orElseThrow(),"author_id"));recipients.add(number(post,"author_id"));recipients.remove(actor);
            for(long recipient:recipients){notice(recipient,"comment:"+id(comment),"comment",comment.get("root_id")==null?"comment":"reply",actor,null,null,"comment",id(comment),"评论与回复","",index++%2==0,later(Timestamp.from(time(comment,"created_at")),at(8*3600)),true);if(index==100)break outer;}
        }
        var noticeReactions=new ArrayList<Reaction>();noticeReactions.addAll(reactions.stream().filter(value->value.table().equals("xl_like")&&value.type().equals("post")).limit(30).toList());noticeReactions.addAll(reactions.stream().filter(value->value.table().equals("xl_favorite")&&value.type().equals("post")).limit(20).toList());
        for(var reaction:noticeReactions){String kind=reaction.table().equals("xl_like")?"post_like":"post_favorite";notice(reaction.owner(),kind+":"+reaction.target()+":"+reaction.actor(),"reaction",kind,reaction.actor(),null,null,reaction.type(),reaction.target(),"互动提醒","",index++%2==0,later(reaction.created(),at(8*3600)),index%9!=0);}
        for(int i=0;i<reports.size();i++){var report=reports.get(i);if("processed".equals(report.get("status")))notice(number(report,"reporter_id"),key("report-result",i+1),"announcement","moderation_warning",null,null,null,null,null,"本地演示举报处理反馈","虚构样本：举报已处理，结果仅供本地测试。",i%2==0,at(3*3600),true);}
        for(int t=1;t<=3;t++){
            boolean sent=t==1;List<Long> recipients=new ArrayList<>();if(sent)for(int i=0;i<90;i++)recipients.add(id(users.get(i)));
            var task=entity("xl_notification_task",row("title","【本地演示】学习与生活提醒 "+t,"body","虚构站内通知，仅向本批测试账号的接收快照展示，不向原有用户发送，不代表微信推送。","audience",sent?"all":"all","status",sent?"sent":"draft","created_by",admin,"updated_by",admin,"client_request_id",key("task",t),"request_hash",hash(key("task",t)),"sent_at",sent?at(6*3600):null,"recipient_count",sent?90:null,"audience_fingerprint",sent?HexFormat.of().formatHex(cn.xinkelink.content.ContentSupport.hash(row("audience","all","recipients",recipients))):null,"send_operation_id",sent?key("task-send",t):null,"created_at",at(7*3600),"updated_at",at(sent?6*3600:7*3600),"version",sent?1:0),"created_by=? AND client_request_id=?",admin,key("task",t)).row();
            for(long recipient:recipients)notice(recipient,"task:"+id(task),"announcement","system",null,id(task),null,null,null,"系统通知","",recipient%2==0,at(6*3600),true);
            audit("notifications","seed_snapshot","notification",id(task),key("task-audit",t),task.get("updated_at"));
        }
    }
    private void notice(long recipient,String key,String type,String kind,Long actor,Long task,Long announcement,String targetType,Long target,String title,String summary,boolean read,Timestamp created,boolean active){
        relation("xl_user_notification",row("recipient_id",recipient,"event_key",key,"type",type,"event_kind",kind,"actor_id",actor,"task_id",task,"announcement_id",announcement,"target_type",targetType,"target_id",target,"title",title,"summary",summary,"read_at",read?later(created,at(3600)):null,"active",active,"created_at",created,"updated_at",read?later(created,at(3600)):created,"version",read?1:0));
    }
    private Map<String,Object> verify()throws Exception{
        Map<String,Integer> actual=new LinkedHashMap<>();String prefix=namespace+":%";
        actual.put("users",count("SELECT COUNT(*) FROM xl_user WHERE wechat_app_id=?",namespace));
        for(var item:Map.of("posts","xl_post","comments","xl_comment","reports","xl_report","announcements","xl_announcement").entrySet())actual.put(item.getKey(),count("SELECT COUNT(*) FROM "+item.getValue()+" WHERE client_request_id LIKE ?",prefix));
        actual.put("messages",count("SELECT COUNT(*) FROM xl_message WHERE client_message_id LIKE ?",prefix));
        actual.put("conversations",count("SELECT COUNT(*) FROM xl_conversation c JOIN xl_user u ON u.id=c.low_user_id JOIN xl_user v ON v.id=c.high_user_id WHERE u.wechat_app_id=? AND v.wechat_app_id=?",namespace,namespace));
        actual.put("relations",count("SELECT (SELECT COUNT(*) FROM xl_like l JOIN xl_user u ON u.id=l.user_id WHERE u.wechat_app_id=?)+(SELECT COUNT(*) FROM xl_favorite f JOIN xl_user u ON u.id=f.user_id WHERE u.wechat_app_id=?)",namespace,namespace));
        actual.put("notifications",count("SELECT COUNT(*) FROM xl_user_notification n JOIN xl_user u ON u.id=n.recipient_id WHERE u.wechat_app_id=?",namespace));
        if(!actual.equals(plan()))throw new IllegalStateException("种子数量不符："+actual);
        zero("SELECT COUNT(*) FROM xl_comment c LEFT JOIN xl_comment r ON r.id=c.root_id LEFT JOIN xl_comment q ON q.id=c.reply_to_id WHERE c.client_request_id LIKE ? AND c.root_id IS NOT NULL AND (r.id IS NULL OR r.root_id IS NOT NULL OR r.target_id<>c.target_id OR r.target_type<>c.target_type OR q.id IS NULL OR q.id<>r.id AND q.root_id<>r.id)",prefix);
        zero("SELECT COUNT(*) FROM xl_conversation c JOIN xl_user u ON u.id=c.low_user_id WHERE u.wechat_app_id=? AND ((SELECT COUNT(*) FROM xl_conversation_member m WHERE m.conversation_id=c.id)<>2 OR c.next_sequence<>(SELECT COALESCE(MAX(sequence_no),0)+1 FROM xl_message x WHERE x.conversation_id=c.id) OR c.last_message_id<>(SELECT x.id FROM xl_message x WHERE x.conversation_id=c.id ORDER BY x.sequence_no DESC LIMIT 1))",namespace);
        zero("SELECT COUNT(*) FROM xl_conversation_member m JOIN xl_conversation c ON c.id=m.conversation_id JOIN xl_user u ON u.id=c.low_user_id WHERE u.wechat_app_id=? AND (m.user_id NOT IN (c.low_user_id,c.high_user_id) OR m.cleared_through_seq>m.read_through_seq OR m.read_through_seq>=c.next_sequence)",namespace);
        zero("SELECT COUNT(*) FROM xl_message m JOIN xl_conversation c ON c.id=m.conversation_id WHERE m.client_message_id LIKE ? AND m.sender_id NOT IN (c.low_user_id,c.high_user_id)",prefix);
        zero("SELECT COUNT(*) FROM xl_report r WHERE r.client_request_id LIKE ? AND r.status='processed' AND NOT EXISTS (SELECT 1 FROM xl_moderation_action a WHERE a.report_id=r.id AND a.action=r.resolution)",prefix);
        zero("SELECT COUNT(*) FROM xl_notification_task t WHERE t.client_request_id LIKE ? AND t.status='sent' AND t.recipient_count<>(SELECT COUNT(DISTINCT recipient_id) FROM xl_user_notification n WHERE n.task_id=t.id)",prefix);
        zero("SELECT COUNT(*) FROM xl_user_notification n JOIN xl_user u ON u.id=n.recipient_id WHERE u.wechat_app_id=? AND n.actor_id=n.recipient_id",namespace);
        zero("SELECT COUNT(*) FROM xl_auth_session s JOIN xl_user u ON u.id=s.subject_id WHERE s.subject_type='user' AND u.wechat_app_id=?",namespace);
        var assets=jdbc.queryForList("SELECT a.* FROM xl_image_asset a JOIN xl_user u ON u.id=a.owner_user_id WHERE u.wechat_app_id=?",namespace);
        for(var asset:assets){Path file=images.resolve(asset.get("storage_key").toString());if(!Files.isRegularFile(file)||Files.isSymbolicLink(file))throw new IllegalStateException("种子图片缺失或非普通文件");byte[] bytes=Files.readAllBytes(file);if(bytes.length!=number(asset,"byte_size")||!MessageDigest.isEqual(hash(bytes),(byte[])asset.get("sha256")))throw new IllegalStateException("种子图片校验不符");}
        return row("namespace",namespace,"expected",plan(),"actual",actual,"inserted",inserted,"mediaCount",assets.size(),"filesCreated",filesCreated,"imageDirectory",images.toString(),"validation","passed","credentialMode","synthetic_non_login","timestamp",Instant.now().toString());
    }
    private long image(long owner,String purpose,String key,String label,int variant)throws Exception{
        Files.createDirectories(images);if(Files.isSymbolicLink(images))throw new IllegalStateException("图片目录不能是符号链接");String storage=UUID.nameUUIDFromBytes((namespace+":"+key).getBytes(StandardCharsets.UTF_8))+".png";byte[] bytes=picture(label,variant);Path path=images.resolve(storage);
        try{Files.write(path,bytes,StandardOpenOption.CREATE_NEW);filesCreated++;}catch(FileAlreadyExistsException existing){if(Files.isSymbolicLink(path)||!Arrays.equals(Files.readAllBytes(path),bytes))throw new IllegalStateException("同名种子图片内容不同，未覆盖");}
        var asset=entity("xl_image_asset",row("owner_user_id",owner,"purpose",purpose,"storage_key",storage,"media_type","image/png","byte_size",bytes.length,"width",256,"height",192,"sha256",hash(bytes),"state","ready","created_at",at(86400),"updated_at",at(86400)),"storage_key=?",storage).row();
        if(number(asset,"owner_user_id")!=owner||!purpose.equals(asset.get("purpose")))throw new IllegalStateException("种子图片归属冲突，未覆盖");mediaCount++;return id(asset);
    }
    static byte[] picture(String label,int variant)throws Exception{
        var image=new BufferedImage(256,192,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try{g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);g.setColor(Color.getHSBColor((variant%17)/17f,.12f,.98f));g.fillRect(0,0,256,192);g.setColor(Color.getHSBColor((variant%17)/17f,.45f,.65f));g.fillRoundRect(72,35,112,88,18,18);g.setColor(new Color(255,255,255,205));g.fillOval(100,53,56,56);g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,18));g.setColor(new Color(42,55,62));g.drawString("本地演示",90,151);g.setFont(new Font(Font.SANS_SERIF,Font.PLAIN,12));g.drawString(label,16,175);}finally{g.dispose();}
        var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();
    }
    private Entity entity(String table,Map<String,Object> values,String where,Object...args){var old=one("SELECT * FROM "+table+" WHERE "+where,args);if(old!=null)return new Entity(old,false);insert(table,values,false);return new Entity(Objects.requireNonNull(one("SELECT * FROM "+table+" WHERE "+where,args)),true);}
    private void relation(String table,Map<String,Object> values){insert(table,values,true);}
    private void insert(String table,Map<String,Object> values,boolean ignore){int count=jdbc.update("INSERT "+(ignore?"IGNORE ":"")+"INTO "+table+" ("+String.join(",",values.keySet())+") VALUES ("+String.join(",",Collections.nCopies(values.size(),"?"))+")",values.values().toArray());inserted.merge(table,count,Integer::sum);}
    private void history(String type,long target,long owner,Long report,String action,String reason,Map<String,Object> before,Map<String,Object> after,Object created,String operation){entity("xl_moderation_action",row("operation_id",operation,"source",report==null?"system":"report","report_id",report,"target_type",type,"target_id",target,"target_user_id",owner,"admin_id",report==null?null:admin,"action",action,"reason",reason,"before_state",json(before),"after_state",json(after),"created_at",created),"operation_id=?",operation);}
    private void audit(String module,String action,String type,long target,String operation,Object created){entity("xl_audit_log",row("operation_id",operation,"actor_type","system","admin_id",null,"actor_display","本地虚构数据生成器","module",module,"action",action,"target_type",type,"target_internal_id",target,"target_count",1,"outcome","success","summary_code","local_seed_snapshot","result_code","OK","changed_fields","[]","created_at",created),"operation_id=?",operation);}
    private Map<String,Object> one(String sql,Object...args){var rows=jdbc.queryForList(sql,args);return rows.isEmpty()?null:rows.getFirst();}
    private int count(String sql,Object...args){return jdbc.queryForObject(sql,Integer.class,args);}
    private void zero(String sql,Object...args){if(count(sql,args)!=0)throw new IllegalStateException("种子关联或状态校验失败");}
    private boolean visiblePost(Map<String,Object> post){return post.get("deleted_at")==null&&post.get("status").equals("visible")&&post.get("review_state").equals("normal");}
    private boolean visibleComment(Map<String,Object> comment){if(comment.get("deleted_at")!=null||!"visible".equals(comment.get("status")))return false;var post=posts.stream().filter(value->id(value)==number(comment,"target_id")).findFirst().orElseThrow();if(!visiblePost(post))return false;if(comment.get("root_id")!=null){var root=comments.stream().filter(value->id(value)==number(comment,"root_id")).findFirst().orElseThrow();return root.get("deleted_at")==null&&root.get("status").equals("visible");}return true;}
    private static Map<String,Object> state(String type,Map<String,Object> value){return row("status",value.get(type.equals("message")?"moderation_state":"status"),"deleted",value.get("deleted_at")!=null,"version",number(value,"version"));}
    private static Map<String,Object> userState(Map<String,Object> value){return row("banState",value.get("ban_state"),"banUntil",Objects.toString(value.get("ban_until"),null),"manualRestrictions",row("publish",flag(value,"restrict_publish"),"comment",flag(value,"restrict_comment"),"message",flag(value,"restrict_message")),"version",number(value,"version"));}
    private static boolean flag(Map<String,Object> row,String field){Object value=row.get(field);return Boolean.TRUE.equals(value)||value instanceof Number number&&number.intValue()!=0;}
    private static long id(Map<String,Object> row){return number(row,"id");}
    private static long number(Map<String,Object> row,String field){return ((Number)row.get(field)).longValue();}
    private static Instant time(Map<String,Object> row,String field){Object value=row.get(field);return value instanceof Timestamp timestamp?timestamp.toInstant():((LocalDateTime)value).toInstant(ZoneOffset.UTC);}
    private Timestamp at(long secondsAgo){return Timestamp.from(anchor.minusSeconds(secondsAgo));}
    private static Timestamp later(Timestamp a,Timestamp b){return a.after(b)?a:b;}
    private String key(String kind,int index){return namespace+":"+kind+":"+String.format("%04d",index);}
    private static String title(Map<String,Object> value){Object title=value.get("title");if(title==null)title=value.get("content");if(title==null)title=value.get("nickname");if(title==null)title="虚构消息";String result=title.toString();return result.substring(0,Math.min(result.length(),60));}
    private static String json(Object value){return JSON.writeValueAsString(value);}
    private static byte[] hash(String value){return hash(value.getBytes(StandardCharsets.UTF_8));}
    private static byte[] hash(byte[] value){try{return MessageDigest.getInstance("SHA-256").digest(value);}catch(Exception exception){throw new IllegalStateException("缺少SHA256",exception);}}
    private static Map<String,Object> row(Object...entries){Map<String,Object> map=new LinkedHashMap<>();for(int i=0;i<entries.length;i+=2)map.put((String)entries[i],entries[i+1]);return map;}
    private record Entity(Map<String,Object> row,boolean created){}
    private record Reaction(String table,String type,long target,long actor,long owner,Timestamp created){}
}
