package cn.xinkelink.beta;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.util.*;
import static cn.xinkelink.beta.BetaModels.*;

@Service @Profile("auth")
public class BetaService {
    public record PhaseChanged(){}
    private record Stage(String phase,boolean initialized,long version){}
    private static final char[] ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private final JdbcTemplate jdbc;
    private final ApplicationEventPublisher events;
    private final SecureRandom random=new SecureRandom();
    public BetaService(JdbcTemplate jdbc,ApplicationEventPublisher events){this.jdbc=jdbc;this.events=events;}

    public boolean isBeta(){return "beta".equals(stage(false).phase());}
    public boolean activated(long userId){return jdbc.queryForObject("SELECT COUNT(*) FROM xl_beta_code WHERE user_id=?",Integer.class,userId)==1;}
    public Access access(AuthPrincipal actor){boolean user=actor!=null&&"user".equals(actor.actorType());return new Access(stage(false).phase(),user,user&&activated(actor.subjectId()));}
    public void requireAccess(AuthPrincipal actor){
        if(isBeta()&&(actor==null||!"admin".equals(actor.actorType())&&(!"user".equals(actor.actorType())||!activated(actor.subjectId()))))
            throw new BusinessException(ErrorCode.BETA_ACCESS_REQUIRED);
    }

    @Transactional
    public Access redeem(AuthPrincipal actor,String input){
        requireUser(actor);
        // 同账号先锁用户，再查绑定；同码先锁码再写入。两个唯一键提供数据库最后防线。
        jdbc.queryForObject("SELECT id FROM xl_user WHERE id=? FOR UPDATE",Long.class,actor.subjectId());
        if(activated(actor.subjectId()))return access(actor);
        String normalized=normalize(input);
        List<Long> codes=jdbc.query("SELECT id FROM xl_beta_code WHERE code=? AND user_id IS NULL FOR UPDATE",(rs,n)->rs.getLong(1),normalized);
        if(codes.isEmpty())throw new BusinessException(ErrorCode.BETA_CODE_UNAVAILABLE);
        int changed=jdbc.update("UPDATE xl_beta_code SET user_id=?,used_at=UTC_TIMESTAMP(3) WHERE id=? AND user_id IS NULL",actor.subjectId(),codes.getFirst());
        if(changed!=1)throw new BusinessException(ErrorCode.BETA_CODE_UNAVAILABLE);
        return access(actor);
    }

    @Transactional
    public Management management(AuthPrincipal admin){
        requireAdmin(admin);
        var stages=jdbc.query("SELECT phase,initialized,version FROM xl_beta_stage WHERE id=1 FOR SHARE",(rs,n)->new Stage(rs.getString(1),rs.getBoolean(2),rs.getLong(3)));
        // Before initialization no batch exists; do not create a singleton during an administrator read.
        return stages.isEmpty()?new Management("open",0,0,0,0,List.of()):snapshot();
    }

    @Transactional
    public Management initialize(AuthPrincipal admin){
        requireAdmin(admin);ensureStage();Stage stage=stage(true);
        if(stage.initialized())return snapshot();
        Set<String> generated=new LinkedHashSet<>();while(generated.size()<30)generated.add(generate());
        jdbc.batchUpdate("INSERT INTO xl_beta_code(code) VALUES (?)",generated.stream().map(code->new Object[]{code}).toList());
        jdbc.update("UPDATE xl_beta_stage SET initialized=1,phase='beta',version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=1");
        phaseChanged();return snapshot();
    }

    @Transactional
    public Management changePhase(AuthPrincipal admin,ChangePhase input){
        requireAdmin(admin);ensureStage();Stage stage=stage(true);
        if(input.expectedVersion()==null||input.expectedVersion()!=stage.version())throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        if(!Set.of("beta","open").contains(input.phase()))throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if("beta".equals(input.phase())&&!stage.initialized())throw new BusinessException(ErrorCode.STATE_CONFLICT);
        if(!stage.phase().equals(input.phase())){
            jdbc.update("UPDATE xl_beta_stage SET phase=?,version=version+1,updated_at=UTC_TIMESTAMP(3) WHERE id=1",input.phase());
            phaseChanged();
        }
        return snapshot();
    }

    private Management snapshot(){
        Stage stage=stage(false);
        List<Code> codes=jdbc.query("SELECT c.id,c.code,c.user_id,u.nickname,c.used_at,c.created_at FROM xl_beta_code c LEFT JOIN xl_user u ON u.id=c.user_id ORDER BY c.id",(rs,n)->{
            String uid=rs.getString("user_id");Timestamp used=rs.getTimestamp("used_at");
            return new Code(rs.getString("id"),rs.getString("code"),uid==null?"unused":"used",uid==null?null:new User(uid,rs.getString("nickname")),used==null?null:used.toInstant(),rs.getTimestamp("created_at").toInstant());
        });
        int used=(int)codes.stream().filter(code->code.user()!=null).count();return new Management(stage.phase(),stage.version(),codes.size(),used,codes.size()-used,codes);
    }
    private Stage stage(boolean lock){var rows=jdbc.query("SELECT phase,initialized,version FROM xl_beta_stage WHERE id=1"+(lock?" FOR UPDATE":""),(rs,n)->new Stage(rs.getString(1),rs.getBoolean(2),rs.getLong(3)));return rows.isEmpty()?new Stage("open",false,0):rows.getFirst();}
    private void ensureStage(){jdbc.update("INSERT INTO xl_beta_stage(id) VALUES(1) ON DUPLICATE KEY UPDATE id=id");}
    private String generate(){StringBuilder code=new StringBuilder("XK-");for(int i=0;i<12;i++){if(i==6)code.append('-');code.append(ALPHABET[random.nextInt(ALPHABET.length)]);}return code.toString();}
    private static String normalize(String value){
        if(value==null||value.length()>256)throw new BusinessException(ErrorCode.BETA_CODE_UNAVAILABLE);
        String compact=value.toUpperCase(Locale.ROOT).replaceAll("[\\s\\p{Z}\\p{Pd}_]","");
        if(!compact.matches("XK[A-Z2-9]{12}"))throw new BusinessException(ErrorCode.BETA_CODE_UNAVAILABLE);
        return "XK-"+compact.substring(2,8)+"-"+compact.substring(8);
    }
    private void requireUser(AuthPrincipal actor){
        if(actor==null)throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if(!"user".equals(actor.actorType()))throw new BusinessException(ErrorCode.FORBIDDEN);
        int valid=jdbc.queryForObject("SELECT COUNT(*) FROM xl_auth_session s JOIN xl_user u ON u.id=s.subject_id WHERE s.id=? AND s.subject_type='user' AND s.subject_id=? AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP(3) AND u.deleted_at IS NULL AND u.ban_state<>'permanent' AND (u.ban_state<>'temporary' OR u.ban_until<=UTC_TIMESTAMP(3))",Integer.class,actor.sessionId(),actor.subjectId());
        if(valid!=1)throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    private void requireAdmin(AuthPrincipal actor){
        if(actor==null)throw new BusinessException(ErrorCode.UNAUTHENTICATED);
        if(!"admin".equals(actor.actorType()))throw new BusinessException(ErrorCode.FORBIDDEN);
        int valid=jdbc.queryForObject("SELECT COUNT(*) FROM xl_auth_session s JOIN xl_admin a ON a.id=s.subject_id WHERE s.id=? AND s.subject_type='admin' AND s.subject_id=? AND s.revoked_at IS NULL AND s.expires_at>UTC_TIMESTAMP(3) AND a.status='active' AND a.role='ADMIN'",Integer.class,actor.sessionId(),actor.subjectId());
        if(valid!=1)throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }
    private void phaseChanged(){TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){events.publishEvent(new PhaseChanged());}});}
}
