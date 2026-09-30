package dk.bankheist;

import com.fasterxml.jackson.databind.*;
import io.javalin.Javalin;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import static dk.bankheist.Protocol.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP requests on ephemeral ports, with deterministic game time. */
class ApiTest {
    private final AtomicLong now = new AtomicLong();
    private Game game;
    private GameServer server;
    private Javalin leaderboard;
    private WebhookDelivery deliveries;
    private Credentials alice, bob;
    @BeforeEach void start() {
        leaderboard = LeaderboardServer.create(new LeaderboardServer.Store()).start(0);
        deliveries = new WebhookDelivery(URI.create("http://localhost:" + leaderboard.port() + "/webhooks/round-ended"), 20, 4);
        game = new Game(now::get, () -> 25_000_000_000L, 0, true);
        server = new GameServer(game, deliveries, "test-key").start(0);
        alice = join("Alice"); bob = join("Bob");
    }
    @AfterEach void stop() { server.close(); leaderboard.stop(); }
    private RequestSpecification api() { return given().port(server.port()).contentType("application/json"); }
    private RequestSpecification teacher() { return api().header("X-Teacher-Key", "test-key"); }
    private RequestSpecification board() { return given().port(leaderboard.port()).contentType("application/json"); }
    private Credentials join(String name) {
        return api().body(Map.of("name", name)).post("/players").then().statusCode(201).extract().as(Credentials.class);
    }
    private Snapshot round() {
        String match = teacher().post("/matches").then().statusCode(201).extract().path("matchId");
        Snapshot round = teacher().post("/matches/" + match + "/rounds").then().statusCode(201).extract().as(Snapshot.class);
        game.tick(); return round;
    }
    @Test void restBaselineAndAuthentication() {
        api().post("/matches").then().statusCode(401);
        api().body("{}").post("/players").then().statusCode(400);
        api().header("Authorization", "Bearer " + alice.reconnectToken()).get("/players/me")
                .then().statusCode(200).body("playerId", equalTo(alice.playerId()));
        Snapshot round = round(); now.set(20_000_000_000L);
        api().post("/rounds/" + round.roundId() + "/escape").then().statusCode(401);
        api().header("Authorization", "Bearer " + alice.reconnectToken())
                .post("/rounds/" + round.roundId() + "/escape")
                .then().statusCode(200).body("outcome", equalTo("ESCAPED"))
                .body("playerId", equalTo(alice.playerId())).body("earnings", equalTo(200f));
        api().get("/state").then().body("phase", equalTo("ACTIVE"))
                .body("players.roundStatus", contains("ESCAPED", "INSIDE"));
        assertTrue(deliveries.list().isEmpty());
        api().header("Authorization", "Bearer " + alice.reconnectToken())
                .post("/rounds/" + round.roundId() + "/escape").then().statusCode(409);
        api().header("Authorization", "Bearer " + bob.reconnectToken())
                .post("/rounds/" + round.roundId() + "/escape").then().statusCode(200);
        api().get("/state").then().statusCode(200).body("phase", equalTo("RESULTS"))
                .body("players.score", contains(200f, 200f));
        await(() -> deliveries.list().stream().anyMatch(d -> d.status().equals("DELIVERED")));
        board().queryParam("matchId", round.matchId()).get("/leaderboard").then().statusCode(200)
                .body("roundsReceived", equalTo(1)).body("scores.score", contains(200f, 200f));
    }
    @Test void teacherResetKicksPlayersAndInvalidatesTheirTokens() {
        api().post("/admin/reset").then().statusCode(401);

        teacher().post("/admin/reset").then().statusCode(200)
                .body("phase", equalTo("LOBBY"))
                .body("roundNumber", equalTo(0))
                .body("players", empty());

        api().header("Authorization", "Bearer " + alice.reconnectToken())
                .get("/players/me").then().statusCode(401);
        teacher().get("/webhook-deliveries").then().body("$", empty());
        teacher().post("/matches").then().statusCode(409);
        join("Alice again");
        api().get("/state").then().body("players.name", contains("Alice again"));
    }
    @Test void expiredEscapeReturnsConflictAndRecordsAlarm() {
        Snapshot round = round(); now.set(10_000_000_000L);
        game.escape(round.roundId(), alice.reconnectToken());
        now.set(25_000_000_000L);
        api().header("Authorization", "Bearer " + bob.reconnectToken())
                .post("/rounds/" + round.roundId() + "/escape").then().statusCode(409);
        api().get("/state").then().body("result.outcome", equalTo("ALARM"))
                .body("players.roundStatus", contains("ESCAPED", "CAUGHT"));
        await(() -> deliveries.list().stream().anyMatch(d -> d.status().equals("DELIVERED")));
        board().queryParam("matchId", round.matchId()).get("/leaderboard").then()
                .body("scores.score", contains(100f, 0f)).body("roundsReceived", equalTo(1));
    }
    @Test void duplicateWebhooksAreAcknowledgedButNotAddedTwice() {
        RoundResult event = example("event-1", "round-1");
        board().body(event).post("/webhooks/round-ended").then().statusCode(200).body("duplicate", is(false));
        board().body(event).post("/webhooks/round-ended").then().statusCode(200).body("duplicate", is(true));
        board().queryParam("matchId", "example").get("/leaderboard").then()
                .body("roundsReceived", equalTo(1)).body("scores.score", contains(200f, 100f));
        board().body(example("event-2", "round-1")).post("/webhooks/round-ended").then().statusCode(409);
        board().body(example("event-1", "round-2")).post("/webhooks/round-ended").then().statusCode(409);
        board().body("{}").post("/webhooks/round-ended").then().statusCode(400);
        board().get("/leaderboard").then().statusCode(400);
    }
    @Test void mixedAlarmWebhookPreservesEscapedGoldAndRejectsInvalidStatuses() {
        RoundResult event = new RoundResult("mixed", "mixed-match", "mixed-round", "ALARM", 250,
                "2026-09-30T12:00:00Z", List.of(new Earning("alice", 100, "ESCAPED"), new Earning("bob", 0, "CAUGHT")));
        board().body(event).post("/webhooks/round-ended").then().statusCode(200);
        board().body(event).post("/webhooks/round-ended").then().statusCode(200).body("duplicate", is(true));
        board().queryParam("matchId", "mixed-match").get("/leaderboard").then()
                .body("scores.score", contains(100f, 0f)).body("roundsReceived", equalTo(1));
        RoundResult invalid = new RoundResult("bad", "mixed-match", "bad-round", "ALL_ESCAPED", 250,
                event.endedAt(), event.earnings());
        board().body(invalid).post("/webhooks/round-ended").then().statusCode(400);
    }
    @Test void manualReplayUsesSameIdAndRemainsIdempotent() {
        Snapshot round = round(); game.escape(round.roundId(), alice.reconnectToken());
        game.escape(round.roundId(), bob.reconnectToken());
        await(() -> deliveries.list().stream().anyMatch(d -> d.status().equals("DELIVERED")));
        String id = game.snapshot().result().eventId();
        teacher().post("/webhook-deliveries/" + id + "/retry").then().statusCode(202);
        await(() -> deliveries.list().get(0).status().equals("DELIVERED"));
        board().queryParam("matchId", round.matchId()).get("/leaderboard").then().body("roundsReceived", equalTo(1));
        teacher().get("/webhook-deliveries").then().body("[0].event.eventId", equalTo(id));
    }
    @Test void websocketSendsSnapshotUpdatesClosureAndReconnectSnapshot() throws Exception {
        BlockingQueue<String> messages = new LinkedBlockingQueue<>();
        WebSocket socket = connect(messages);
        ObjectMapper json = new ObjectMapper();
        JsonNode initial = json.readTree(messages.poll(3, TimeUnit.SECONDS));
        assertEquals("STATE_SNAPSHOT", initial.path("type").asText());
        Snapshot round = round(); now.set(1_000_000_000L); game.tick();
        game.escape(round.roundId(), alice.reconnectToken());
        // Reconnect mid-round: one player is safe while the other is still inside.
        BlockingQueue<String> midway = new LinkedBlockingQueue<>();
        WebSocket midSocket = connect(midway);
        JsonNode midState = json.readTree(midway.poll(3, TimeUnit.SECONDS)).path("state");
        assertEquals("ACTIVE", midState.path("phase").asText());
        assertEquals("ESCAPED", midState.path("players").get(0).path("roundStatus").asText());
        assertEquals("INSIDE", midState.path("players").get(1).path("roundStatus").asText());
        midSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
        game.escape(round.roundId(), bob.reconnectToken());
        Set<String> types = new HashSet<>(); long revision = initial.path("revision").asLong();
        for (int i = 0; i < 30 && !types.contains("ROUND_ENDED"); i++) {
            String raw = messages.poll(3, TimeUnit.SECONDS); assertNotNull(raw);
            JsonNode message = json.readTree(raw); types.add(message.path("type").asText());
            // A connection snapshot can overtake already queued older messages; clients discard those.
            revision = Math.max(revision, message.path("revision").asLong());
            assertFalse(raw.contains("reconnectToken")); assertFalse(raw.contains("deadline"));
        }
        assertTrue(types.containsAll(Set.of("ROUND_STARTED", "LOOT_UPDATED", "PLAYER_ESCAPED", "ROUND_ENDED")));
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "test complete").join();
        BlockingQueue<String> reconnected = new LinkedBlockingQueue<>();
        WebSocket second = connect(reconnected);
        JsonNode snapshot = json.readTree(reconnected.poll(3, TimeUnit.SECONDS));
        assertEquals("RESULTS", snapshot.path("state").path("phase").asText());
        assertEquals(game.snapshot().result().eventId(), snapshot.path("state").path("result").path("eventId").asText());
        assertTrue(snapshot.path("revision").asLong() >= revision);
        second.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }

    private WebSocket connect(BlockingQueue<String> messages) {
        return HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3))
                .buildAsync(URI.create("ws://localhost:" + server.port() + "/ws/game"), new WebSocket.Listener() {
                    private final StringBuilder buffer = new StringBuilder();
                    @Override public void onOpen(WebSocket ws) { ws.request(1); }
                    @Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                        buffer.append(data);
                        if (last) { messages.add(buffer.toString()); buffer.setLength(0); }
                        ws.request(1); return CompletableFuture.completedFuture(null);
                    }
                }).join();
    }
    static RoundResult example(String eventId, String roundId) {
        return new RoundResult(eventId, "example", roundId, "ALL_ESCAPED", 200,
                "2026-09-30T12:00:00Z", List.of(new Earning("alice", 100, "ESCAPED"), new Earning("bob", 200, "ESCAPED")));
    }
    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Condition did not become true within five seconds");
            try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }
}
