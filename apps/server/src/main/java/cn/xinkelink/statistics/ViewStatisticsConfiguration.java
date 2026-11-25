package cn.xinkelink.statistics;

import cn.xinkelink.auth.token.TokenProperties;
import cn.xinkelink.forum.ForumService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;

@Configuration(proxyBeanMethods=false) @Profile("auth") @EnableConfigurationProperties(ViewStatisticsProperties.class)
public class ViewStatisticsConfiguration {
    @Bean VisitorCredentials visitorCredentials(TokenProperties tokens,Clock clock){return new VisitorCredentials(tokens.secretBase64(),clock);}
    @Bean ViewKafkaTransport viewKafkaTransport(ViewStatisticsProperties p){return new ViewKafkaTransport(p);}
    @Bean ViewPublisher viewPublisher(ViewJournal j,ViewKafkaTransport t,MeterRegistry m){return new ViewPublisher(j,t,m);}
    @Bean ViewProjection viewProjection(JdbcTemplate j,StringRedisTemplate r,PlatformTransactionManager tm,MeterRegistry m,ViewStatisticsProperties p,Clock c){return new ViewProjection(j,r,new TransactionTemplate(tm),m,p.getNamespace(),c);}
    @Bean ViewRankingService viewRankingService(ViewProjection p,ForumService f){return new ViewRankingService(p,f);}
    @Bean ViewStatisticsWorkers viewStatisticsWorkers(ViewStatisticsProperties p,ViewPublisher pub,ViewKafkaTransport t,ViewProjection projection,MeterRegistry m,JdbcTemplate j){return new ViewStatisticsWorkers(p,pub,t,projection,m,j);}
    @Bean ViewConsumer viewConsumer(ViewStatisticsProperties p,ViewProcessor processor,ViewFailures f,MeterRegistry m){return new ViewConsumer(p,processor,f,m);}
}
