package cn.xinkelink.seed;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.file.Path;
import java.sql.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class LocalSeedDataIT {
    @TempDir Path images;
    @Test void fullDatasetIsIdempotentPreservesExistingRowsAndRollsBack()throws Exception{
        String namespace="seed-it-"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        String url=System.getenv("XINKELINK_DB_URL");LocalSeedData.checkDatabase(url);
        try(Connection connection=DriverManager.getConnection(url,System.getenv("XINKELINK_DB_USERNAME"),System.getenv("XINKELINK_DB_PASSWORD"))){
            connection.setAutoCommit(false);var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            try {
                jdbc.update("INSERT INTO xl_user(wechat_app_id,wechat_open_id,nickname) VALUES (?,'unrelated','保留原资料')",namespace+"-old");
                var first=LocalSeedData.populate(connection,images,namespace);
                assertEquals(100,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE wechat_app_id=?",Integer.class,namespace));
                assertEquals(LocalSeedData.plan(),first.get("actual"));
                assertTrue(((Number)((Map<?,?>)first.get("inserted")).get("xl_message")).intValue()>0);
                long user=jdbc.queryForObject("SELECT id FROM xl_user WHERE wechat_app_id=? AND wechat_open_id='local-user-001'",Long.class,namespace);
                jdbc.update("UPDATE xl_user SET nickname='人工保留的修改',bio='不应被重复生成覆盖' WHERE id=?",user);
                long post=jdbc.queryForObject("SELECT id FROM xl_post WHERE client_request_id=?",Long.class,namespace+":post:0002");
                jdbc.update("UPDATE xl_post SET content='用户自行修改的帖子' WHERE id=?",post);
                jdbc.update("DELETE FROM xl_media_ref WHERE owner_type='post' AND owner_id=? AND position=1",post);
                var second=LocalSeedData.populate(connection,images,namespace);
                assertTrue(((Map<?,?>)second.get("inserted")).values().stream().allMatch(value->((Number)value).intValue()==0));
                assertEquals("人工保留的修改",jdbc.queryForObject("SELECT nickname FROM xl_user WHERE id=?",String.class,user));
                assertEquals("用户自行修改的帖子",jdbc.queryForObject("SELECT content FROM xl_post WHERE id=?",String.class,post));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_media_ref WHERE owner_type='post' AND owner_id=? AND position=1",Integer.class,post));
                assertEquals("保留原资料",jdbc.queryForObject("SELECT nickname FROM xl_user WHERE wechat_app_id=?",String.class,namespace+"-old"));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_auth_session WHERE subject_type='user' AND subject_id IN (SELECT id FROM xl_user WHERE wechat_app_id=?)",Integer.class,namespace));
                assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_comment c JOIN xl_comment r ON r.id=c.root_id WHERE c.client_request_id LIKE ? AND (r.root_id IS NOT NULL OR c.target_id<>r.target_id OR c.target_type<>r.target_type)",Integer.class,namespace+":%"));
                assertTrue(((Number)first.get("mediaCount")).intValue()>500);
            }finally{connection.rollback();}
        }
        try(Connection connection=DriverManager.getConnection(url,System.getenv("XINKELINK_DB_USERNAME"),System.getenv("XINKELINK_DB_PASSWORD"))){
            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM xl_user WHERE wechat_app_id=?",Integer.class,namespace));
        }
    }
}
