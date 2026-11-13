package cn.xinkelink.moderation;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;

import static cn.xinkelink.moderation.ModerationRepository.*;

@Service
@Profile("auth")
public class BanExpiryService {
    private final ModerationRepository repository;
    private final Clock clock;
    public BanExpiryService(ModerationRepository repository,Clock clock){this.repository=repository;this.clock=clock;}
    @Transactional
    public void normalize(long userId){
        Map<String,Object> row=repository.user(userId,false);
        if(!expired(row))return;
        row=repository.user(userId,true);
        if(!expired(row))return;
        Map<String,Object> before=userState(row);
        // 只移除已到期的临时封禁层，人工三通道限制独立保留。
        repository.jdbc.update("UPDATE xl_user SET ban_state='none',ban_until=NULL,updated_at=UTC_TIMESTAMP(3),version=version+1 WHERE id=?",userId);
        repository.history("system",null,"user",userId,userId,null,"temporary_ban_expired","临时封禁已到期",before,userState(repository.user(userId,false)));
    }
    private boolean expired(Map<String,Object> row){return row!=null&&"temporary".equals(row.get("ban_state"))&&time(row,"ban_until")!=null&&!time(row,"ban_until").isAfter(clock.instant());}
}
