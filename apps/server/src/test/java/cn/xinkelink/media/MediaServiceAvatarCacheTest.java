package cn.xinkelink.media;

import cn.xinkelink.auth.token.TokenProperties;
import cn.xinkelink.common.api.BusinessException;
import cn.xinkelink.common.api.ErrorCode;
import cn.xinkelink.media.persistence.MediaMapper;
import cn.xinkelink.media.persistence.MediaMapper.Asset;
import cn.xinkelink.media.persistence.MediaMapper.Parent;
import cn.xinkelink.media.persistence.MediaMapper.Ref;
import cn.xinkelink.storage.ImageStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MediaServiceAvatarCacheTest {
    private static final long AVATAR_ID = 17;
    private static final long OWNER_ID = 24;
    private static final String STORAGE_KEY = "avatar-storage-key";
    private static final Instant NOW = Instant.parse("2026-11-22T13:00:00Z");
    private static final byte[] IMAGE_BYTES = {1, 2, 3};

    private final MediaMapper mapper = mock(MediaMapper.class);
    private final MediaContentReader reader = mock(MediaContentReader.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final MediaGrant grants = new MediaGrant(
            new TokenProperties(Base64.getEncoder().encodeToString(new byte[32]), "test"), clock);
    private final MediaService service = new MediaService(
            mapper, mock(ImageStorage.class), reader, grants, clock, mock(PlatformTransactionManager.class));

    @BeforeEach
    void existingPublicAvatar() {
        when(mapper.find(AVATAR_ID)).thenReturn(new Asset(AVATAR_ID, OWNER_ID, "avatar", STORAGE_KEY,
                "image/png", IMAGE_BYTES.length, 1, 1, "ready", null, null));
        when(mapper.refs(AVATAR_ID)).thenReturn(List.of(new Ref("user_avatar", OWNER_ID, AVATAR_ID, 0)));
        when(mapper.avatars(List.of(OWNER_ID))).thenReturn(List.of(new Parent(OWNER_ID, OWNER_ID, true, false)));
    }

    @Test
    void differentGrantsReuseAvatarBytesAndStillCheckTheCurrentReference() throws IOException {
        when(reader.read(STORAGE_KEY)).thenReturn(IMAGE_BYTES);
        String first = publicGrant();
        String second = publicGrant();
        assertNotEquals(first, second);
        assertArrayEquals(IMAGE_BYTES, service.content(AVATAR_ID, first));
        assertArrayEquals(IMAGE_BYTES, service.content(AVATAR_ID, second));

        when(mapper.refs(AVATAR_ID)).thenReturn(List.of());
        assertEquals(ErrorCode.IMAGE_FORBIDDEN,
                assertThrows(BusinessException.class, () -> service.content(AVATAR_ID, second)).code());
        verify(reader, times(1)).read(STORAGE_KEY);
    }

    @Test
    void readerFailureCanRecoverOnTheNextRequest() throws IOException {
        when(reader.read(STORAGE_KEY)).thenThrow(new IOException("transient storage error")).thenReturn(IMAGE_BYTES);
        assertEquals(ErrorCode.STORAGE_UNAVAILABLE,
                assertThrows(BusinessException.class, () -> service.content(AVATAR_ID, publicGrant())).code());
        assertArrayEquals(IMAGE_BYTES, service.content(AVATAR_ID, publicGrant()));
        assertArrayEquals(IMAGE_BYTES, service.content(AVATAR_ID, publicGrant()));
        verify(reader, times(2)).read(STORAGE_KEY);
    }

    private String publicGrant() {
        return grants.issue(new MediaGrant.Claim(AVATAR_ID, NOW.plusSeconds(60), "user_avatar", OWNER_ID, 0, 0));
    }
}
