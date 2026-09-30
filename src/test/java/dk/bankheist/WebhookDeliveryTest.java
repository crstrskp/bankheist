package dk.bankheist;

import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.concurrent.atomic.*;
import static dk.bankheist.ApiTest.*;
import static org.junit.jupiter.api.Assertions.*;

class WebhookDeliveryTest {
    @Test void failedDeliveryRetriesWithoutChangingEventId() {
        AtomicInteger calls = new AtomicInteger();
        LeaderboardServer.Store store = new LeaderboardServer.Store();
        Javalin receiver = Javalin.create().post("/hook", ctx -> {
            var event = ctx.bodyAsClass(Protocol.RoundResult.class);
            store.accept(event); // Simulate accepting the event but losing the acknowledgement.
            if (calls.incrementAndGet() == 1) ctx.status(503); else ctx.status(200);
        }).start(0);
        try (WebhookDelivery sender = new WebhookDelivery(URI.create("http://localhost:" + receiver.port() + "/hook"), 20, 3)) {
            sender.enqueue(example("retry-event", "retry-round"));
            await(() -> sender.list().get(0).status().equals("DELIVERED"));
            assertEquals(2, calls.get()); assertEquals(2, sender.list().get(0).attempts());
            assertEquals(1, store.board("example").roundsReceived());
            assertEquals(200, store.board("example").scores().get(0).score());
        } finally { receiver.stop(); }
    }
    @Test void retriesAreBoundedAndManualRetryRecoversAfterReceiverReturns() {
        AtomicBoolean failing = new AtomicBoolean(true);
        Javalin receiver = Javalin.create().post("/hook", ctx -> ctx.status(failing.get() ? 503 : 200)).start(0);
        try (WebhookDelivery sender = new WebhookDelivery(URI.create("http://localhost:" + receiver.port() + "/hook"), 10, 3)) {
            sender.enqueue(example("bounded", "bounded-round"));
            await(() -> sender.list().get(0).status().equals("FAILED"));
            assertEquals(3, sender.list().get(0).attempts());
            failing.set(false); assertTrue(sender.retry("bounded"));
            await(() -> sender.list().get(0).status().equals("DELIVERED"));
            assertEquals(1, sender.list().get(0).attempts());
            assertFalse(sender.retry("missing"));
        } finally { receiver.stop(); }
    }
}
