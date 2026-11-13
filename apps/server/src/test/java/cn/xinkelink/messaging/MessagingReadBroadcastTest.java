package cn.xinkelink.messaging;

import cn.xinkelink.auth.AuthPrincipal;
import cn.xinkelink.content.ContentCursor;
import cn.xinkelink.content.ContentSupport;
import cn.xinkelink.notification.NotificationService;
import cn.xinkelink.realtime.RealtimeEvents;
import org.junit.jupiter.api.Test;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MessagingReadBroadcastTest {
    @Test void unchangedReadWatermarkDoesNotBroadcastAgain() {
        MessagingStore store = mock(MessagingStore.class);
        ContentSupport support = mock(ContentSupport.class);
        ContentCursor cursors = mock(ContentCursor.class);
        NotificationService notifications = mock(NotificationService.class);
        RealtimeEvents events = mock(RealtimeEvents.class);
        var snapshot = new MessagingStore.Conversation(7, 10, 20, BigInteger.TWO, 1L,
                Instant.now(), Instant.now(), BigInteger.ONE, BigInteger.ZERO, false, false, 1);
        when(support.require(any(), isNull())).thenReturn(20L);
        when(store.conversation(7, 20, true)).thenReturn(snapshot);
        when(store.conversation(7, 20, false)).thenReturn(snapshot);
        when(store.read(eq(7L), eq(20L), any())).thenReturn(1, 0);
        when(store.unreads(eq(20L), anyList())).thenReturn(Map.of());
        when(store.messages(anyList())).thenReturn(List.of());
        when(support.users(anyCollection())).thenReturn(Map.of(10L, Map.of("id", "10")));
        when(notifications.unreadForUser(20L)).thenReturn(Map.of());
        doAnswer(call -> { ((Runnable) call.getArgument(0)).run(); return null; })
                .when(events).afterCommit(any(Runnable.class));

        MessagingService service = new MessagingService(store, support, cursors, notifications, events);
        var actor = new AuthPrincipal(20, 1, "user");
        service.read(7, new MessagingModels.Read("1"), actor);
        clearInvocations(events);

        service.read(7, new MessagingModels.Read("1"), actor);

        verify(events, never()).publish(anyLong(), anyString(), anyMap());
    }
}
