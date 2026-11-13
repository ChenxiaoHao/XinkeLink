package cn.xinkelink.content;

import cn.xinkelink.auth.token.TokenProperties;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentCursorTest {
    private final Instant now=Instant.parse("2026-10-24T12:00:00Z");
    private final TokenProperties properties=new TokenProperties(Base64.getEncoder().encodeToString(new byte[32]),"test");
    private ContentCursor service(Instant time){return new ContentCursor(properties,Clock.fixed(time,ZoneOffset.UTC));}
    @Test void bindsTheCursorToQueryAndViewerAndRejectsTampering() {
        ContentCursor cursor=service(now);
        var first=cursor.read(null,"posts:latest",1L);
        var position=new ContentCursor.Position(first.snapshot(),now.minusSeconds(2),9,null);
        String token=cursor.next("posts:latest",1L,position);
        assertEquals(position,cursor.read(token,"posts:latest",1L));
        for(String scope:new String[]{"posts:recommended","posts:study"}) assertInvalid(()->cursor.read(token,scope,1L));
        assertInvalid(()->cursor.read(token,"posts:latest",2L));
        assertInvalid(()->cursor.read(token.substring(0,token.length()-4)+"AAAA","posts:latest",1L));
        assertInvalid(()->service(now.plusSeconds(3601)).read(token,"posts:latest",1L));
    }
    @Test void carriesPriceTieBreakerAndRejectsMalformedTokens() {
        ContentCursor cursor=service(now);
        var value=new ContentCursor.Position(now,now.minusMillis(1),5,100L);
        assertEquals(value,cursor.read(cursor.next("price_asc",null,value),"price_asc",null));
        for(String token:new String[]{"","junk","..","a.b","x".repeat(5000)})assertInvalid(()->cursor.read(token,"price_asc",null));
    }
    @Test void usesDatabaseTimeSoNewlyCommittedRowsAreNotLostToClockSkew(){
        ContentMapper mapper=mock(ContentMapper.class);
        Instant databaseNow=now.plusMillis(5);
        when(mapper.currentTime()).thenReturn(databaseNow);
        ContentCursor cursor=new ContentCursor(properties,Clock.fixed(now,ZoneOffset.UTC),mapper);
        var first=cursor.read(null,"posts:latest",null);
        assertEquals(databaseNow,first.snapshot());
        var next=new ContentCursor.Position(first.snapshot(),databaseNow.minusMillis(1),1,null);
        assertEquals(next,cursor.read(cursor.next("posts:latest",null,next),"posts:latest",null));
    }
    private void assertInvalid(org.junit.jupiter.api.function.Executable action){assertEquals(ErrorCode.CURSOR_INVALID,assertThrows(BusinessException.class,action).code());}
}
