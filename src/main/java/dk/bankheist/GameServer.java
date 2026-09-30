package dk.bankheist;

import io.javalin.Javalin;
import io.javalin.http.*;
import io.javalin.websocket.WsContext;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import static dk.bankheist.Protocol.*;

public final class GameServer implements AutoCloseable {
    private final Game game;
    private final WebhookDelivery webhooks;
    private final Set<WsContext> clients = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService broadcaster = Executors.newSingleThreadExecutor();
    private final Javalin app;
    public GameServer(Game game, WebhookDelivery webhooks, String teacherKey) {
        this.game = game; this.webhooks = webhooks;
        app = Javalin.create(c -> c.staticFiles.add("/public"));
        app.get("/health", ctx -> ctx.json(Map.of("status", "ok")));
        app.get("/state", ctx -> ctx.json(game.snapshot()));
        app.post("/players", ctx -> {
            JoinRequest request;
            try { request = ctx.bodyAsClass(JoinRequest.class); }
            catch (Exception e) { throw new BadRequestResponse("Expected JSON with a name"); }
            if (request == null) throw new BadRequestResponse("Expected JSON with a name");
            ctx.status(201).json(game.join(request.name()));
        });
        app.get("/players/me", ctx -> ctx.json(game.identity(token(ctx))));
        app.post("/matches", ctx -> { teacher(ctx, teacherKey); ctx.status(201).json(game.createMatch()); });
        app.post("/matches/{matchId}/rounds", ctx -> {
            teacher(ctx, teacherKey); ctx.status(201).json(game.startRound(ctx.pathParam("matchId")));
        });
        app.post("/rounds/{roundId}/escape", ctx -> ctx.json(game.escape(ctx.pathParam("roundId"), token(ctx))));
        app.get("/webhook-deliveries", ctx -> { teacher(ctx, teacherKey); ctx.json(webhooks.list()); });
        app.post("/webhook-deliveries/{eventId}/retry", ctx -> {
            teacher(ctx, teacherKey);
            if (!webhooks.retry(ctx.pathParam("eventId"))) throw new NotFoundResponse("Unknown event ID");
            ctx.status(202).json(Map.of("queued", true));
        });
        app.ws("/ws/game", ws -> {
            ws.onConnect(ctx -> {
                // Serialize registration with broadcasts so the first frame is always a snapshot,
                // and no transition can be missed between taking that snapshot and subscribing.
                synchronized (clients) {
                    ctx.send(game.snapshotMessage());
                    clients.add(ctx);
                }
                ctx.enableAutomaticPings(15, TimeUnit.SECONDS);
            });
            ws.onClose(clients::remove);
            ws.onError(clients::remove);
        });
    }
    private static String token(Context ctx) {
        String auth = ctx.header("Authorization");
        return auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : "";
    }
    private static void teacher(Context ctx, String key) {
        if (!key.equals(ctx.header("X-Teacher-Key"))) throw new UnauthorizedResponse("Teacher key required");
    }
    public GameServer start(int port) {
        app.start(port);
        broadcaster.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Message message = game.events().take();
                    // Enqueue delivery before sending sockets; neither operation holds the game lock.
                    if (message.type().equals("ROUND_ENDED")) webhooks.enqueue(message.state().result());
                    synchronized (clients) {
                        for (WsContext client : clients) {
                            try { client.send(message); } catch (Exception ex) { clients.remove(client); }
                        }
                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        timer.scheduleAtFixedRate(game::tick, 0, 250, TimeUnit.MILLISECONDS);
        return this;
    }
    public int port() { return app.port(); }
    @Override public void close() {
        timer.shutdownNow(); broadcaster.shutdownNow(); webhooks.close(); app.stop();
    }
    public static void main(String[] args) {
        GameServer server = new GameServer(new Game(Boolean.parseBoolean(System.getenv().getOrDefault("ALARM_ENABLED", "true"))),
                new WebhookDelivery(URI.create(System.getenv().getOrDefault("WEBHOOK_URL", "http://localhost:7071/webhooks/round-ended"))),
                System.getenv().getOrDefault("TEACHER_KEY", "classroom"))
                .start(Integer.parseInt(System.getenv().getOrDefault("GAME_PORT", "7070")));
        Runtime.getRuntime().addShutdownHook(new Thread(server::close));
    }
}
