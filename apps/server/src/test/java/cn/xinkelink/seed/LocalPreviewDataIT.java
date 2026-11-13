package cn.xinkelink.seed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalPreviewDataIT {
    @TempDir Path images;
    @Test void smallPreviewUsesPhotosArchivesOnlyItsBulkBatchAndIsIdempotent() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-", "").substring(0,12), ns="preview-it-"+suffix, old="bulk-it-"+suffix;
        String url=System.getenv("XINKELINK_DB_URL");LocalSeedData.checkDatabase(url);
        try(var connection=DriverManager.getConnection(url,System.getenv("XINKELINK_DB_USERNAME"),System.getenv("XINKELINK_DB_PASSWORD"))){
            connection.setAutoCommit(false);var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {
                jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES (?,'one','旧批次'),(?,'one','保留账号')",old,ns+"-keep");
                long oldUser=jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id=?",Long.class,old);
                jdbc.update("INSERT INTO xl_post(author_id,category,content,client_request_id,request_hash) VALUES (?,'campus','旧批次正文',?,UNHEX(SHA2('old',256)))",oldUser,old+":post:1");
                var first=LocalPreviewData.populate(connection,images,ns,old);
                assertEquals(LocalPreviewData.plan(),first.get("actual"));
                assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE wechat_app_id=? AND avatar_image_id IS NOT NULL",Integer.class,ns));
                assertNotNull(jdbc.queryForObject("SELECT deleted_at FROM xl_user WHERE id=?",Timestamp.class,oldUser));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_post WHERE client_request_id=? AND deleted_at IS NOT NULL",Integer.class,old+":post:1"));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE wechat_app_id=? AND deleted_at IS NULL",Integer.class,ns+"-keep"));
                long post=jdbc.queryForObject("SELECT id FROM xl_post WHERE client_request_id=?",Long.class,ns+":post:1");
                jdbc.update("UPDATE xl_post SET content='用户后来修改的内容' WHERE id=?",post);
                var second=LocalPreviewData.populate(connection,images,ns,old);
                assertEquals(0,second.get("inserted"));
                assertEquals("用户后来修改的内容",jdbc.queryForObject("SELECT content FROM xl_post WHERE id=?",String.class,post));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_auth_session s JOIN xl_user u ON u.id=s.subject_id WHERE u.wechat_app_id=? AND s.subject_type='user'",Integer.class,ns));
                assertTrue(jdbc.queryForObject("SELECT MIN(width) FROM xl_image_asset a JOIN xl_user u ON u.id=a.owner_user_id WHERE u.wechat_app_id=?",Integer.class,ns)>1000);
                long recipient=jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id=?",Long.class,ns+"-keep");
                var extras=LocalPreviewExtras.apply(connection,images,ns,"legacy-it-"+suffix,recipient);
                assertEquals(4,extras.get("conversationsCreated"));
                assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM xl_conversation WHERE low_user_id=? OR high_user_id=?",Integer.class,recipient,recipient));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_message WHERE sender_id=?",Integer.class,recipient));
                assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM xl_message m JOIN xl_user u ON u.id=m.sender_id WHERE u.wechat_app_id=?",Integer.class,ns));
                assertEquals("保留账号",jdbc.queryForObject("SELECT nickname FROM xl_user WHERE id=?",String.class,recipient));
                // 再次执行不能重置真实用户后来已读的水位，也不重写用户编辑的资料。
                jdbc.update("UPDATE xl_conversation_member SET read_through_seq=1 WHERE user_id=?",recipient);
                long firstPreviewUser=jdbc.queryForObject("SELECT MIN(id) FROM xl_user WHERE wechat_app_id=?",Long.class,ns);
                jdbc.update("UPDATE xl_user SET nickname='后来修改的昵称' WHERE id=?",firstPreviewUser);
                assertEquals(0,LocalPreviewExtras.apply(connection,images,ns,"legacy-it-"+suffix,recipient).get("inserted"));
                assertEquals(4,jdbc.queryForObject("SELECT COUNT(*) FROM xl_conversation_member WHERE user_id=? AND read_through_seq=1",Integer.class,recipient));
                assertEquals("后来修改的昵称",jdbc.queryForObject("SELECT nickname FROM xl_user WHERE id=?",String.class,firstPreviewUser));
                assertThrows(IllegalArgumentException.class,()->LocalPreviewExtras.apply(connection,images,ns,"legacy-it-"+suffix,firstPreviewUser));
            } finally {connection.rollback();}
        }
    }
}
