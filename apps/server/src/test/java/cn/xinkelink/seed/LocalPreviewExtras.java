package cn.xinkelink.seed;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Instant;
import java.util.*;

/** 仅本机种子工具使用：小规模人物资料、旧联调记录软归档与测试联系会话。 */
final class LocalPreviewExtras {
    private final JdbcTemplate jdbc;
    private final String ns;
    private final Path directory;
    private int inserted, archived, conversations;
    private LocalPreviewExtras(Connection connection,Path directory,String ns)throws SQLException {
        if(connection.getAutoCommit()||(!ns.equals(LocalPreviewData.NAMESPACE)&&!ns.matches("preview-it-[a-f0-9]{12}")))throw new IllegalArgumentException("需要预览批次事务");
        this.jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));this.ns=ns;this.directory=directory;
    }
    static Map<String,Integer> apply(Connection connection,Path directory,String ns,String legacy,Long contactUser)throws Exception {
        if(!legacy.equals("day5-test-adapter")&&!legacy.matches("legacy-it-[a-f0-9]{12}"))throw new IllegalArgumentException("仅允许归档已知联调批次");
        var data=new LocalPreviewExtras(connection,directory,ns);data.students(legacy);
        if(contactUser!=null)data.contacts(contactUser);
        return Map.of("inserted",data.inserted,"archived",data.archived,"conversationsCreated",data.conversations);
    }
    private void students(String legacy)throws Exception {
        String marker=ns+":students-v2";if(marked(marker))return;
        var users=users();if(users.size()!=4)throw new IllegalStateException("预览用户数量异常");
        String[] names={"林知夏","周予安","苏禾","陈屿"},photos={"avatar-lin","avatar-zhou","avatar-su","avatar-chen"};
        String[] bios={"喜欢拍晚霞，偶尔分享读书笔记","软件工程在读，最近在补数据结构","课后散步、看书，也折腾一点电子小玩意","羽毛球新手，周末有空一起运动"};
        for(int i=0;i<4;i++) {
            long user=id(users.get(i));long image=avatar(user,photos[i]);
            jdbc.update("UPDATE xl_user SET nickname=?,bio=?,avatar_image_id=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",names[i],bios[i],image,user);
            jdbc.update("INSERT INTO xl_media_ref(owner_type,owner_id,position,image_id) VALUES('user_avatar',?,0,?)",user,image);
        }
        // 仅归档明确属于旧测试身份的内容，不删除历史或触碰真实微信账号。
        for(String table:List.of("xl_post","xl_comment")) {
            String owner="author_id";
            archived+=jdbc.update("UPDATE "+table+" t JOIN xl_user u ON u.id=t."+owner+" SET t.deleted_at=UTC_TIMESTAMP(3),t.updated_at=UTC_TIMESTAMP(3),t.version=t.version+1 WHERE u.wechat_app_id=? AND t.deleted_at IS NULL",legacy);
        }
        archived+=jdbc.update("UPDATE xl_user SET deleted_at=UTC_TIMESTAMP(3),updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE wechat_app_id=? AND deleted_at IS NULL",legacy);
        mark(marker,"preview_students",archived);
    }
    private long avatar(long user,String photo)throws Exception {
        Path root=Path.of("").toAbsolutePath();if(root.endsWith(Path.of("apps","server")))root=root.getParent().getParent();
        var decoded=ImageIO.read(root.resolve("docs/assets/local-preview/"+photo+".png").toFile());
        if(decoded==null||decoded.getWidth()<512||decoded.getHeight()<512)throw new IllegalStateException("头像素材无效");
        var out=new ByteArrayOutputStream();ImageIO.write(decoded,"png",out);byte[] bytes=out.toByteArray();
        String storage=UUID.nameUUIDFromBytes((ns+":"+photo).getBytes(StandardCharsets.UTF_8))+".png";
        Files.createDirectories(directory);Path target=directory.resolve(storage);
        try{Files.write(target,bytes,StandardOpenOption.CREATE_NEW);}catch(FileAlreadyExistsException e){if(!Arrays.equals(bytes,Files.readAllBytes(target)))throw new IllegalStateException("头像文件冲突，未覆盖");}
        jdbc.update("INSERT INTO xl_image_asset(owner_user_id,purpose,storage_key,media_type,byte_size,width,height,sha256,state) VALUES(?,'avatar',?,'image/png',?,?,?,?,'ready')",user,storage,bytes.length,decoded.getWidth(),decoded.getHeight(),hash(bytes));inserted++;
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
    }
    private void contacts(long recipient) {
        var recipientRows=jdbc.queryForList("SELECT id,wechat_app_id FROM xl_user WHERE id=? AND deleted_at IS NULL AND ban_state='none' AND restrict_message=FALSE FOR UPDATE",recipient);
        if(recipientRows.isEmpty()||Objects.toString(recipientRows.getFirst().get("wechat_app_id")).equals(ns))throw new IllegalArgumentException("联系接收者必须是其他有效本地账号");
        String marker=ns+":contacts:"+recipient;if(marked(marker))return;
        String[] texts={"你好呀，我是知夏。喜欢拍照和散步，有空可以交流校园生活。","你好，我是予安。最近在整理高数笔记，有空可以交流复习方法。","你好呀，我是苏禾。喜欢阅读和校园活动，有空可以一起聊聊。","你好，我是陈屿。平时喜欢打羽毛球，有空可以交流下练球的方法。"};
        var peers=users();for(int i=0;i<peers.size();i++) {
            long peer=id(peers.get(i)),low=Math.min(peer,recipient),high=Math.max(peer,recipient);
            jdbc.queryForList("SELECT id FROM xl_user WHERE id IN (?,?) ORDER BY id FOR UPDATE",low,high);
            var existing=jdbc.queryForList("SELECT id FROM xl_conversation WHERE low_user_id=? AND high_user_id=?",low,high);
            // 已有会话的消息、阅读水位和用户偏好一律保留。
            if(!existing.isEmpty())continue;
            Timestamp created=Timestamp.from(Instant.now().minusSeconds((4-i)*300L));
            jdbc.update("INSERT INTO xl_conversation(low_user_id,high_user_id,created_at,updated_at) VALUES(?,?,?,?)",low,high,created,created);
            long conversation=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);inserted++;conversations++;
            jdbc.update("INSERT INTO xl_conversation_member(conversation_id,user_id,read_through_seq) VALUES(?,?,0),(?,?,1)",conversation,recipient,conversation,peer);inserted+=2;
            String client="preview-contact-"+recipient+"-"+(i+1);
            jdbc.update("INSERT INTO xl_message(conversation_id,sender_id,sequence_no,client_message_id,request_hash,text,created_at,updated_at) VALUES(?,?,1,?,?,?,?,?)",conversation,peer,client,hash((conversation+"|"+texts[i]).getBytes(StandardCharsets.UTF_8)),texts[i],created,created);inserted++;
            long message=jdbc.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
            jdbc.update("UPDATE xl_conversation SET next_sequence=2,last_message_id=?,last_message_at=? WHERE id=?",message,created,conversation);
        }
        mark(marker,"preview_contacts",conversations);
    }
    private List<Map<String,Object>> users(){return jdbc.queryForList("SELECT id FROM xl_user WHERE wechat_app_id=? AND deleted_at IS NULL ORDER BY wechat_open_id",ns);}
    private boolean marked(String key){
        var rows=jdbc.queryForList("SELECT actor_type,module,action FROM xl_audit_log WHERE operation_id=?",key);
        if(rows.isEmpty())return false;
        var row=rows.getFirst();if(!"system".equals(row.get("actor_type"))||!"settings".equals(row.get("module"))||!Set.of("preview_students","preview_contacts").contains(row.get("action")))throw new IllegalStateException("预览标记冲突");
        return true;
    }
    private void mark(String key,String action,int count){jdbc.update("INSERT INTO xl_audit_log(operation_id,actor_type,actor_display,module,action,target_type,target_count,outcome,summary_code,result_code,changed_fields) VALUES(?,'system','本地预览数据工具','settings',?,'seed_batch',?,'success','local_preview','OK','[]')",key,action,count);inserted++;}
    private static long id(Map<String,Object> row){return ((Number)row.get("id")).longValue();}
    private static byte[] hash(byte[] bytes){try{return MessageDigest.getInstance("SHA-256").digest(bytes);}catch(Exception e){throw new IllegalStateException(e);}}
}
