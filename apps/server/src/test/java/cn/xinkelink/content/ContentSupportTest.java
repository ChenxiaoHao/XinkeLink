package cn.xinkelink.content;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.auth.persistence.AuthMapper;
import cn.xinkelink.auth.persistence.UserAccount;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContentSupportTest {
    @Test void checksCurrentManualRestrictionsAndKeepsReadAccess() {
        AuthMapper auth = mock(AuthMapper.class);
        ContentSupport support = new ContentSupport(auth, mock(ContentMapper.class));
        when(auth.findUser(1)).thenReturn(new UserAccount(1,"同学",null,"","","",null,"none",null,true,false,false,null,0));
        AuthPrincipal viewer = new AuthPrincipal(1,1,"user");
        assertEquals(1,support.require(viewer,null));
        assertEquals(ErrorCode.ACTION_RESTRICTED,assertThrows(BusinessException.class,()->support.require(viewer,"publish")).code());
        assertEquals(ErrorCode.FORBIDDEN,assertThrows(BusinessException.class,()->support.require(new AuthPrincipal(1,1,"admin"),null)).code());
        assertEquals(ErrorCode.UNAUTHENTICATED,assertThrows(BusinessException.class,()->support.require(null,null)).code());
    }
    @Test void preservesVersionAndStrictIfMatchPreconditions() {
        assertEquals(0,ContentSupport.ifMatch("\"0\""));
        assertEquals(ErrorCode.PRECONDITION_REQUIRED,assertThrows(BusinessException.class,()->ContentSupport.ifMatch(null)).code());
        for(String value:new String[]{"*","W/\"0\"","0","\"-1\"","\"0\",\"1\""}) assertThrows(BusinessException.class,()->ContentSupport.ifMatch(value));
        assertEquals(ErrorCode.VERSION_CONFLICT,assertThrows(BusinessException.class,()->ContentSupport.version(1,0L)).code());
        assertDoesNotThrow(()->ContentSupport.version(1,1L));
    }
    @Test void validatesBoundsAndHashesCanonicalMaps() {
        assertEquals(20,ContentSupport.size(null));
        assertThrows(BusinessException.class,()->ContentSupport.size(51));
        assertThrows(BusinessException.class,()->ContentSupport.id("0"));
        assertThrows(BusinessException.class,()->ContentSupport.id("9223372036854775808"));
        assertEquals("同学",ContentSupport.keyword(" 同学 "));
        assertArrayEquals(ContentSupport.hash(Map.of("a",1,"b",2)),ContentSupport.hash(ContentSupport.map("b",2,"a",1)));
        assertNull(ContentSupport.map("avatar",null).get("avatar"));
    }
}
