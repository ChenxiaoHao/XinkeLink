package cn.xinkelink.statistics;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.forum.ForumService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ViewProjectionTest {
    final Clock clock=Clock.fixed(Instant.parse("2026-11-16T16:01:00Z"),ZoneOffset.UTC);
    final StringRedisTemplate redis=mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked") final ValueOperations<String,String> values=mock(ValueOperations.class);
    final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    final TransactionTemplate transactions=new TransactionTemplate(mock(PlatformTransactionManager.class));
    final JsonMapper json=JsonMapper.builder().build();
    final ViewProjection projection=new ViewProjection(jdbc,redis,transactions,new SimpleMeterRegistry(),"isolated_test",clock);

    @Test void windowIsSevenBeijingCalendarDaysAndMissingProjectionIsHonestEmpty() {
        when(redis.opsForValue()).thenReturn(values);
        var snapshot=projection.read();
        assertEquals(LocalDate.of(2026,11,11),snapshot.startDate());
        assertEquals(LocalDate.of(2026,11,17),snapshot.endDate());
        assertFalse(snapshot.available()); assertTrue(snapshot.entries().isEmpty()); assertNull(snapshot.updatedAt());
        verifyNoInteractions(jdbc);
    }
    @Test void previousDayAndExpiredSnapshotsNeverBecomeTodayRanking() {
        when(redis.opsForValue()).thenReturn(values);
        var stale=new ViewProjection.Snapshot(1,LocalDate.of(2026,11,10),LocalDate.of(2026,11,16),clock.instant(),List.of(new ViewProjection.Entry(2,5,1)),true);
        when(values.get(anyString())).thenReturn(json.writeValueAsString(stale));
        assertFalse(projection.read().available());
        var expired=new ViewProjection.Snapshot(2,LocalDate.of(2026,11,11),LocalDate.of(2026,11,17),clock.instant().minusSeconds(61),stale.entries(),true);
        when(values.get(anyString())).thenReturn(json.writeValueAsString(expired));
        assertFalse(projection.read().available());
    }
    @Test void redisOutageAndCorruptProjectionAreErrorsRatherThanFakeSuccess() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenThrow(new IllegalStateException("connection details must not escape"));
        var failure=assertThrows(BusinessException.class,projection::read);
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,failure.code()); assertNull(failure.getCause());
        doReturn("not-json").when(values).get(anyString());
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE,assertThrows(BusinessException.class,projection::read).code());
    }
    @Test void namespaceCannotTouchOtherRedisData() {
        assertThrows(IllegalArgumentException.class,()->new ViewProjection(jdbc,redis,transactions,new SimpleMeterRegistry(),"../cache:*",clock));
    }
    @Test void lateTaskCannotPublishAfterNewGenerationEvenIfAllRedisKeysWereLost() {
        when(jdbc.queryForObject("SELECT generation FROM xl_view_projection WHERE id=1 FOR UPDATE",Long.class)).thenReturn(9L);
        var old=new ViewProjection.Snapshot(8,LocalDate.of(2026,11,11),LocalDate.of(2026,11,17),clock.instant(),List.of(),true);
        assertFalse(projection.publish(old));
        verifyNoInteractions(redis);
    }
    @Test void capturedBeforeBeijingMidnightButPublishedAfterMidnightIsNeverShownAsToday() {
        var captured=new ViewProjection.Snapshot(1,LocalDate.of(2026,11,10),LocalDate.of(2026,11,16),
                Instant.parse("2026-11-16T15:59:59.999Z"),List.of(new ViewProjection.Entry(2,5,1)),true);
        Clock afterMidnight=Clock.fixed(Instant.parse("2026-11-16T16:00:00.001Z"),ZoneOffset.UTC);
        var after=new ViewProjection(jdbc,redis,transactions,new SimpleMeterRegistry(),"isolated_test",afterMidnight);
        when(jdbc.queryForObject("SELECT generation FROM xl_view_projection WHERE id=1 FOR UPDATE",Long.class)).thenReturn(1L);
        when(redis.execute(any(org.springframework.data.redis.core.script.RedisScript.class),anyList(),any(),any(),any())).thenReturn(1L);
        assertTrue(after.publish(captured));
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenReturn(json.writeValueAsString(captured));
        assertFalse(after.read().available());
        assertTrue(after.read().entries().isEmpty());
        assertEquals(LocalDate.of(2026,11,17),after.read().endDate());
    }
    @Test void rankingFiltersAndContinuesPastFirstFiftyWithExistingPublicProjection() {
        var source=mock(ViewProjection.class); var forum=mock(ForumService.class);
        var actor=new AuthPrincipal(8,2,"user");
        var entries=new ArrayList<ViewProjection.Entry>();
        for(long id=1;id<=70;id++)entries.add(new ViewProjection.Entry(id,100,100-id));
        when(source.read()).thenReturn(new ViewProjection.Snapshot(5,LocalDate.of(2026,11,11),LocalDate.of(2026,11,17),clock.instant(),entries,true));
        when(forum.favoriteItems(anyCollection(),eq(actor))).thenAnswer(invocation->{
            Collection<Long> ids=invocation.getArgument(0); Map<Long,Map<String,Object>> result=new HashMap<>();
            for(long id:ids)if(id>=61)result.put(id,Map.of("id",Long.toString(id),"author",Map.of("isAnonymous",true)));
            return result;
        });
        var response=new ViewRankingService(source,forum).read(actor);
        @SuppressWarnings("unchecked") var items=(List<Map<String,Object>>)response.get("items");
        assertEquals(5,items.size());
        assertEquals(List.of("61","62","63","64","65"),items.stream().map(item->((Map<?,?>)item.get("post")).get("id")).toList());
        assertEquals(39L,items.getFirst().get("uv")); assertEquals(100L,items.getFirst().get("pv"));
        assertFalse(json.writeValueAsString(response).contains("visitor"));
        assertEquals("Asia/Shanghai",((Map<?,?>)response.get("window")).get("zone"));
        verify(forum,atLeast(2)).favoriteItems(anyCollection(),eq(actor));
    }
    @Test void emptyRankingDoesNotLoadPostsOrFallbackToLikes() {
        var source=mock(ViewProjection.class); var forum=mock(ForumService.class);
        when(source.read()).thenReturn(new ViewProjection.Snapshot(0,LocalDate.of(2026,11,11),LocalDate.of(2026,11,17),null,List.of(),false));
        var response=new ViewRankingService(source,forum).read(null);
        assertEquals(List.of(),response.get("items")); assertEquals(false,response.get("available"));
        verifyNoInteractions(forum);
    }
}
