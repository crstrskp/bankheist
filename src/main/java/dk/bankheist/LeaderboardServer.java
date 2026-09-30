package dk.bankheist;

import io.javalin.Javalin;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import java.time.Instant;
import java.util.*;
import static dk.bankheist.Protocol.*;

public final class LeaderboardServer {
    public record Score(String playerId, double score) {}
    public record Board(String matchId, int roundsReceived, List<Score> scores) {}
    public static final class Store {
        private final Map<String, RoundResult> events = new HashMap<>();
        private final Map<String, String> rounds = new HashMap<>();
        private final Map<String, Map<String, Double>> scores = new HashMap<>();
        public synchronized boolean accept(RoundResult event) {
            validate(event);
            RoundResult previous = events.get(event.eventId());
            if (previous != null) {
                if (!previous.equals(event)) throw new ConflictResponse("Event ID reused with a different payload");
                return false;
            }
            String roundKey = event.matchId() + ":" + event.roundId();
            if (rounds.containsKey(roundKey)) throw new ConflictResponse("Round already recorded with another event ID");
            Map<String, Double> totals = scores.computeIfAbsent(event.matchId(), k -> new LinkedHashMap<>());
            event.earnings().forEach(e -> totals.merge(e.playerId(), e.earnings(), Double::sum));
            events.put(event.eventId(), event); rounds.put(roundKey, event.eventId()); return true;
        }
        public synchronized Board board(String matchId) {
            List<Score> rows = scores.getOrDefault(matchId, Map.of()).entrySet().stream()
                    .map(e -> new Score(e.getKey(), e.getValue()))
                    .sorted(Comparator.comparingDouble(Score::score).reversed().thenComparing(Score::playerId)).toList();
            return new Board(matchId, (int) events.values().stream().filter(e -> e.matchId().equals(matchId)).count(), rows);
        }
        private static void validate(RoundResult e) {
            if (e == null || blank(e.eventId()) || blank(e.matchId()) || blank(e.roundId()) || blank(e.endedAt())
                    || !Set.of("ALL_ESCAPED", "ALARM").contains(e.outcome() == null ? "" : e.outcome())
                    || !Double.isFinite(e.bagValue()) || e.bagValue() < 0 || e.earnings().size() < 2 || e.earnings().size() > 8)
                throw new BadRequestResponse("Invalid round result");
            try { Instant.parse(e.endedAt()); } catch (RuntimeException ex) { throw new BadRequestResponse("Invalid endedAt"); }
            Set<String> ids = new HashSet<>();
            for (Earning earning : e.earnings())
                if (earning == null || blank(earning.playerId()) || !Double.isFinite(earning.earnings()) || earning.earnings() < 0
                        || !Set.of("ESCAPED", "CAUGHT").contains(earning.status() == null ? "" : earning.status())
                        || earning.status().equals("CAUGHT") && earning.earnings() != 0
                        || !ids.add(earning.playerId()))
                    throw new BadRequestResponse("Invalid or duplicate earnings entry");
            boolean anyCaught = e.earnings().stream().anyMatch(x -> x.status().equals("CAUGHT"));
            if (e.outcome().equals("ALL_ESCAPED") && anyCaught || e.outcome().equals("ALARM") && !anyCaught)
                throw new BadRequestResponse("Outcome does not match player statuses");
        }
        private static boolean blank(String s) { return s == null || s.isBlank(); }
    }
    public static Javalin create(Store store) {
        Javalin app = Javalin.create();
        app.get("/health", ctx -> ctx.json(Map.of("status", "ok")));
        app.post("/webhooks/round-ended", ctx -> {
            RoundResult result;
            try { result = ctx.bodyAsClass(RoundResult.class); }
            catch (Exception e) { throw new BadRequestResponse("Invalid round result JSON"); }
            boolean applied = store.accept(result);
            ctx.json(Map.of("accepted", true, "duplicate", !applied));
        });
        app.get("/leaderboard", ctx -> {
            String matchId = ctx.queryParam("matchId");
            if (matchId == null || matchId.isBlank()) throw new BadRequestResponse("matchId is required");
            ctx.json(store.board(matchId));
        });
        return app;
    }
    public static void main(String[] args) {
        Javalin app = create(new Store()).start(Integer.parseInt(System.getenv().getOrDefault("LEADERBOARD_PORT", "7071")));
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop));
    }
}
