package cn.xinkelink.messaging;

import cn.xinkelink.content.ContentCursor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;

@Repository
@Profile("auth")
public class MessagingStore {
    record Conversation(long id,long low,long high,BigInteger next,Long lastMessage,Instant activity,Instant updated,
            BigInteger read,BigInteger clear,boolean pinned,boolean muted,long version) {
        long peer(long user){return low==user?high:low;}
    }
    record Message(long id,long conversation,long sender,BigInteger sequence,String client,byte[] hash,String text,
            String visibility,Instant created) {}
    record Member(long conversation,long user,BigInteger read,BigInteger clear) {}
    record Watermark(long conversation,BigInteger highest) {}
    private static final String CONVERSATION="""
            SELECT c.id,c.low_user_id,c.high_user_id,c.next_sequence,c.last_message_id,
            COALESCE(c.last_message_at,CAST('1970-01-01 00:00:00.000' AS DATETIME(3))) activity,c.updated_at,
            m.read_through_seq,m.cleared_through_seq,m.pinned,m.muted,m.version
            FROM xl_conversation c JOIN xl_conversation_member m ON m.conversation_id=c.id
            """;
    private final JdbcTemplate jdbc;
    public MessagingStore(JdbcTemplate jdbc){this.jdbc=jdbc;}
    private static Instant instant(ResultSet rs,String name)throws SQLException{return rs.getTimestamp(name).toInstant();}
    private static BigInteger number(ResultSet rs,String name)throws SQLException{return rs.getBigDecimal(name).toBigIntegerExact();}
    private static Long nullableLong(ResultSet rs,String name)throws SQLException {long value=rs.getLong(name);return rs.wasNull()?null:value;}
    private static final RowMapper<Conversation> CONVERSATIONS=(rs,n)->new Conversation(rs.getLong("id"),rs.getLong("low_user_id"),
            rs.getLong("high_user_id"),number(rs,"next_sequence"),nullableLong(rs,"last_message_id"),instant(rs,"activity"),
            instant(rs,"updated_at"),number(rs,"read_through_seq"),number(rs,"cleared_through_seq"),rs.getBoolean("pinned"),rs.getBoolean("muted"),rs.getLong("version"));
    private static final RowMapper<Message> MESSAGES=(rs,n)->new Message(rs.getLong("id"),rs.getLong("conversation_id"),
            rs.getLong("sender_id"),number(rs,"sequence_no"),rs.getString("client_message_id"),rs.getBytes("request_hash"),
            rs.getString("text"),rs.getString("moderation_state"),instant(rs,"created_at"));
    boolean userExists(long user){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM xl_user WHERE id=? AND deleted_at IS NULL)",Boolean.class,user));}
    void lockUsers(long low,long high){jdbc.query("SELECT id FROM xl_user WHERE id IN (?,?) ORDER BY id FOR UPDATE",(rs,n)->rs.getLong(1),low,high);}
    long open(long low,long high){
        jdbc.update("INSERT INTO xl_conversation(low_user_id,high_user_id) VALUES(?,?) ON DUPLICATE KEY UPDATE id=id",low,high);
        long id=Objects.requireNonNull(jdbc.queryForObject("SELECT id FROM xl_conversation WHERE low_user_id=? AND high_user_id=?",Long.class,low,high));
        jdbc.update("INSERT INTO xl_conversation_member(conversation_id,user_id) VALUES(?,?),(?,?) ON DUPLICATE KEY UPDATE user_id=user_id",id,low,id,high);
        return id;
    }
    Conversation conversation(long id,long user,boolean lock){
        var rows=jdbc.query(CONVERSATION+" WHERE c.id=? AND m.user_id=?"+(lock?" FOR UPDATE":""),CONVERSATIONS,id,user);
        return rows.isEmpty()?null:rows.getFirst();
    }
    List<Conversation> conversations(long user,ContentCursor.Position p,int limit){
        List<Object> args=new ArrayList<>(List.of(user,java.sql.Timestamp.from(p.snapshot()),java.sql.Timestamp.from(p.snapshot())));
        // 无消息会话的活动排序值统一置底，同时按创建时间固定本页快照上界。
        String activity="COALESCE(c.last_message_at,CAST('1970-01-01 00:00:00.000' AS DATETIME(3)))";
        String key="";
        if(p.at()!=null){
            key=" AND (m.pinned<? OR (m.pinned=? AND ("+activity+"<? OR ("+activity+"=? AND c.id<?))))";
            args.addAll(List.of(p.value(),p.value(),java.sql.Timestamp.from(p.at()),java.sql.Timestamp.from(p.at()),p.id()));
        }
        args.add(limit);
        return jdbc.query(CONVERSATION+" WHERE m.user_id=? AND c.created_at<=? AND "+activity+"<=?"+key+
                " ORDER BY m.pinned DESC,activity DESC,c.id DESC LIMIT ?",CONVERSATIONS,args.toArray());
    }
    boolean blocked(long owner,long peer){return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM xl_user_block WHERE owner_id=? AND blocked_user_id=?)",Boolean.class,owner,peer));}
    Message request(long user,String client){var rows=jdbc.query("SELECT * FROM xl_message WHERE sender_id=? AND client_message_id=?",MESSAGES,user,client);return rows.isEmpty()?null:rows.getFirst();}
    Message message(long id){var rows=jdbc.query("SELECT * FROM xl_message WHERE id=?",MESSAGES,id);return rows.isEmpty()?null:rows.getFirst();}
    Message insert(Conversation conversation,long user,String client,byte[] hash,String text){
        jdbc.update("INSERT INTO xl_message(conversation_id,sender_id,sequence_no,client_message_id,request_hash,text) VALUES(?,?,?,?,?,?)",
                conversation.id(),user,conversation.next().toString(),client,hash,text);
        Message message=Objects.requireNonNull(request(user,client));
        jdbc.update("UPDATE xl_conversation SET next_sequence=next_sequence+1,last_message_id=?,last_message_at=?,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",
                message.id(),java.sql.Timestamp.from(message.created()),conversation.id());
        return message;
    }
    List<Message> history(long conversation,BigInteger clear,BigInteger before,BigInteger after,String pattern,int limit){
        String where=" WHERE conversation_id=? AND sequence_no>?";
        List<Object> args=new ArrayList<>(List.of(conversation,clear.toString()));
        if(before!=null){where+=" AND sequence_no<?";args.add(before.toString());}
        if(after!=null){where+=" AND sequence_no>?";args.add(after.toString());}
        if(pattern!=null){where+=" AND moderation_state='normal' AND text LIKE ? ESCAPE '!'";args.add(pattern);}
        args.add(limit);
        return jdbc.query("SELECT * FROM xl_message"+where+" ORDER BY sequence_no "+(after!=null?"ASC":"DESC")+" LIMIT ?",MESSAGES,args.toArray());
    }
    int read(long conversation,long user,BigInteger sequence){return jdbc.update("""
            UPDATE xl_conversation_member SET read_through_seq=GREATEST(read_through_seq,?),updated_at=UTC_TIMESTAMP(3)
            WHERE conversation_id=? AND user_id=? AND read_through_seq<?
            """,sequence.toString(),conversation,user,sequence.toString());}
    void clear(long conversation,long user,BigInteger sequence){jdbc.update("""
            UPDATE xl_conversation_member SET cleared_through_seq=?,read_through_seq=GREATEST(read_through_seq,?),updated_at=UTC_TIMESTAMP(3)
            WHERE conversation_id=? AND user_id=?
            """,sequence.toString(),sequence.toString(),conversation,user);}
    void settings(long conversation,long user,boolean pinned,boolean muted){jdbc.update("""
            UPDATE xl_conversation_member SET pinned=?,muted=?,version=version+1,updated_at=UTC_TIMESTAMP(3)
            WHERE conversation_id=? AND user_id=?
            """,pinned,muted,conversation,user);}
    List<Watermark> capture(long user){return jdbc.query("""
            SELECT c.id,c.next_sequence-1 highest FROM xl_conversation c
            JOIN xl_conversation_member m ON m.conversation_id=c.id WHERE m.user_id=? ORDER BY c.id
            """,(rs,n)->new Watermark(rs.getLong("id"),number(rs,"highest")),user);}
    Map<Long,Long> unreads(long user,Collection<Long> ids){
        Map<Long,Long> result=new HashMap<>();if(ids.isEmpty())return result;
        List<Object> args=new ArrayList<>(List.of(user,user));args.addAll(ids);
        jdbc.query("""
                SELECT msg.conversation_id,COUNT(*) total FROM xl_message msg JOIN xl_conversation_member m
                ON m.conversation_id=msg.conversation_id AND m.user_id=? WHERE msg.sender_id<>?
                AND msg.moderation_state='normal' AND msg.sequence_no>GREATEST(m.read_through_seq,m.cleared_through_seq)
                AND msg.conversation_id IN (
                """+placeholders(ids.size())+") GROUP BY msg.conversation_id",rs->{result.put(rs.getLong("conversation_id"),rs.getLong("total"));},args.toArray());
        return result;
    }
    List<Message> messages(Collection<Long> ids){return ids.isEmpty()?List.of():jdbc.query("SELECT * FROM xl_message WHERE id IN ("+placeholders(ids.size())+")",MESSAGES,ids.toArray());}
    private static String placeholders(int count){return String.join(",",Collections.nCopies(count,"?"));}
}
