package cn.xinkelink.forum;

import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.media.MediaService;
import cn.xinkelink.forum.metrics.PostMetricsCache;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PostMetricsProjectionTest {
    @Test void publicNumbersAreSharedButViewerStateIsAlwaysQueriedSeparately() {
        var mapper=mock(ForumMapper.class);var support=mock(ContentSupport.class);var media=mock(MediaService.class);
        var cache=mock(PostMetricsCache.class);
        when(support.viewer(isNull())).thenReturn(null);
        var row=new ForumModels.PostRow(1,9,"campus","public",true,"匿名同学test","visible","normal",new byte[32],Instant.now(),0,null);
        when(mapper.post(1)).thenReturn(row);when(media.images(eq("post"),anyList(),isNull())).thenReturn(Map.of());
        when(support.users(anyList())).thenReturn(Map.of());
        when(cache.get(anyCollection(),any())).thenReturn(Map.of(1L,new PostMetricsCache.Counts(4,5)));
        when(mapper.postViewerStates(anyCollection(),isNull())).thenReturn(List.of(new ForumModels.PostViewerState(1,false,false)));
        var service=new ForumService(mapper,support,null,media,null,cache);
        var view=service.detail(1,null);
        assertEquals(4L,view.get("likeCount"));assertEquals(5L,view.get("commentCount"));
        assertEquals(false,view.get("liked"));assertEquals(false,view.get("isMine"));
        assertNull(((Map<?,?>)view.get("author")).get("id"));
        verify(mapper).postViewerStates(List.of(1L),null);
    }
    @Test void hiddenPostCannotBeReadEvenIfMetricsCacheHasAValue() {
        var mapper=mock(ForumMapper.class);var cache=mock(PostMetricsCache.class);
        when(mapper.post(1)).thenReturn(new ForumModels.PostRow(1,9,"campus","hidden",false,null,"hidden","normal",new byte[32],Instant.now(),0,null));
        var service=new ForumService(mapper,mock(ContentSupport.class),null,null,null,cache);
        assertThrows(cn.xinkelink.common.api.BusinessException.class,()->service.detail(1,null));
        verifyNoInteractions(cache);
    }
}
