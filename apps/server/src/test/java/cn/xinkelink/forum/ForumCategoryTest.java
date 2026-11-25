package cn.xinkelink.forum;

import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.content.ContentCursor;
import cn.xinkelink.content.ContentSupport;
import jakarta.validation.Validation;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ForumCategoryTest {
    @ParameterizedTest
    @ValueSource(strings={"campus","lost_found","study","help","secondhand","complain","buddy"})
    void acceptsPublishAndEditCategories(String category) {
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertTrue(validator.validate(new ForumModels.CreatePost("校园帖子",category,List.of(),false,"category-test")).isEmpty());
            assertTrue(validator.validate(new ForumModels.UpdatePost(null,category,null,0L)).isEmpty());
        }
    }
    @ParameterizedTest
    @ValueSource(strings={"campus","lost_found","study","help","secondhand","complain","buddy"})
    void listsEachCategoryThroughServiceValidation(String category) {
        var mapper=mock(ForumMapper.class);
        var cursors=mock(ContentCursor.class);
        when(cursors.read(isNull(),anyString(),nullable(Long.class))).thenReturn(new ContentCursor.Position(Instant.now(),null,0,null));
        var service=new ForumService(mapper,mock(ContentSupport.class),cursors,null,null,null);
        assertTrue(service.list(category,"latest",null,null,10,null).items().isEmpty());
    }
    @ParameterizedTest
    @ValueSource(strings={"recommended","latest","unknown","product"})
    void sortNamesAndRetiredMarketplaceAreNotCategories(String category) {
        var service=new ForumService(null,null,null,null,null,null);
        assertEquals(ErrorCode.VALIDATION_FAILED,assertThrows(BusinessException.class,
                ()->service.list(category,"latest",null,null,10,null)).code());
        try(var factory=Validation.buildDefaultValidatorFactory()) {
            assertFalse(factory.getValidator().validate(new ForumModels.CreatePost("内容",category,List.of(),false,"category-test")).isEmpty());
        }
    }
}


