package dk.bankheist;

import io.javalin.http.BadRequestResponse;
import io.javalin.http.ConflictResponse;
import io.javalin.http.UnauthorizedResponse;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.LongSupplier;
import static dk.bankheist.Protocol.*;

/** All state transitions share one monitor. No socket or HTTP work happens here. */
public final class Game {
    private record Player(String id, String name, String token) {}
    private final Map<String, Player> players = new LinkedHashMap<>();
    private final Map<String, Double> scores = new LinkedHashMap<>();
    private final Map<String, Earning> banked = new LinkedHashMap<>();
    private final LongSupplier nanoClock;
    private final LongSupplier alarmDuration;
    private final long countdownNanos;
    private final BlockingQueue<Message> events = new LinkedBlockingQueue<>();
    private String matchId, roundId;
    private String phase = "LOBBY";
    private int roundNumber;
    private long revision, startsAt, deadline;
    private final boolean alarmEnabled;
    private RoundResult result;

    public Game(boolean alarmEnabled) {
        this(System::nanoTime, () -> java.util.concurrent.ThreadLocalRandom.current()
                .nextLong(5_000_000_000L, 25_000_000_001L), 3_000_000_000L, alarmEnabled);
    }
    Game(LongSupplier nanoClock, LongSupplier alarmDuration, long countdownNanos, boolean alarmEnabled) {
        this.nanoClock = nanoClock; this.alarmDuration = alarmDuration;
        this.countdownNanos = countdownNanos; this.alarmEnabled = alarmEnabled;
    }
    public BlockingQueue<Message> events() { return events; }

    public synchronized Credentials join(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 30)
            throw new BadRequestResponse("Name must contain 1–30 characters");
        if (players.size() >= 8) throw new ConflictResponse("The eight player slots are full");
        String id = UUID.randomUUID().toString();
        Player p = new Player(id, name.strip(), UUID.randomUUID().toString());
        players.put(id, p);
        emit("STATE_SNAPSHOT");
        return new Credentials(id, p.token());
    }
    public synchronized PlayerView identity(String token) {
        Player p = authenticate(token);
        return view(p, snapshot().bagValue());
    }
    private Player authenticate(String token) {
        return players.values().stream().filter(p -> p.token().equals(token)).findFirst()
                .orElseThrow(() -> new UnauthorizedResponse("Unknown player token; join first"));
    }
    public synchronized Snapshot createMatch() {
        if (!phase.equals("LOBBY") && !phase.equals("FINISHED"))
            throw new ConflictResponse("Finish the current match first");
        if (players.size() < 2) throw new ConflictResponse("At least two players must join");
        scores.clear(); banked.clear(); players.keySet().forEach(id -> scores.put(id, 0.0));
        matchId = UUID.randomUUID().toString(); roundId = null; roundNumber = 0;
        phase = "READY"; result = null;
        emit("STATE_SNAPSHOT"); return snapshot();
    }
    public synchronized Snapshot reset() {
        players.clear();
        scores.clear();
        banked.clear();
        matchId = null;
        roundId = null;
        roundNumber = 0;
        startsAt = 0;
        deadline = 0;
        phase = "LOBBY";
        result = null;
        emit("GAME_RESET");
        return snapshot();
    }
    public synchronized Snapshot startRound(String requestedMatch) {
        if (!Objects.equals(matchId, requestedMatch) || !(phase.equals("READY") || phase.equals("RESULTS")))
            throw new ConflictResponse("Match is stale or not ready for another round");
        roundId = UUID.randomUUID().toString(); roundNumber++;
        startsAt = nanoClock.getAsLong() + countdownNanos;
        deadline = startsAt + alarmDuration.getAsLong();
        phase = "COUNTDOWN"; result = null; banked.clear();
        emit("STATE_SNAPSHOT"); return snapshot();
    }
    public synchronized EscapeResult escape(String requestedRound, String token) {
        Player p = authenticate(token);
        if (!scores.containsKey(p.id())) throw new ConflictResponse("Spectators cannot escape");
        if (!Objects.equals(roundId, requestedRound)) throw new ConflictResponse("Stale round ID");
        long now = nanoClock.getAsLong();
        advance(now);
        if (!phase.equals("ACTIVE")) throw new ConflictResponse("Round is not active or has already ended");
        if (banked.containsKey(p.id())) throw new ConflictResponse("You have already escaped this round");
        // Each personal bag grows at 10 gold/second, independent of the crew size.
        double earned = bagValueAt(now);
        banked.put(p.id(), new Earning(p.id(), earned, "ESCAPED"));
        scores.put(p.id(), scores.get(p.id()) + earned);
        emit("PLAYER_ESCAPED");
        if (banked.size() == scores.size()) close("ALL_ESCAPED", now);
        return new EscapeResult(matchId, roundId, p.id(), "ESCAPED", earned);
    }
    public synchronized void tick() {
        advance(nanoClock.getAsLong());
        if (phase.equals("ACTIVE") || phase.equals("COUNTDOWN")) emit("LOOT_UPDATED");
    }
    private void advance(long now) {
        if (phase.equals("COUNTDOWN") && now - startsAt >= 0) {
            phase = "ACTIVE"; emit("ROUND_STARTED");
        }
        if (phase.equals("ACTIVE") && alarmEnabled && now - deadline >= 0) close("ALARM", deadline);
    }
    private double bagValueAt(long time) {
        return Math.max(0, (time - startsAt) / 1_000_000_000.0) * 10;
    }
    private void close(String outcome, long end) {
        // Escaped players were already credited. Only remaining players are caught.
        for (String id : scores.keySet()) banked.putIfAbsent(id, new Earning(id, 0, "CAUGHT"));
        List<Earning> earnings = scores.keySet().stream().map(banked::get).toList();
        result = new RoundResult(UUID.randomUUID().toString(), matchId, roundId, outcome,
                bagValueAt(end), Instant.now().toString(), earnings);
        phase = roundNumber == 10 ? "FINISHED" : "RESULTS";
        emit("ROUND_ENDED");
    }
    private PlayerView view(Player p, double bagValue) {
        boolean participant = scores.containsKey(p.id());
        Earning earning = banked.get(p.id());
        String status = !participant ? "SPECTATING" : earning != null ? earning.status()
                : phase.equals("ACTIVE") ? "INSIDE" : "WAITING";
        return new PlayerView(p.id(), p.name(), scores.getOrDefault(p.id(), 0.0), participant,
                status, earning == null ? 0 : earning.earnings(),
                status.equals("INSIDE") ? bagValue : 0);
    }
    public synchronized Snapshot snapshot() {
        long now = nanoClock.getAsLong();
        double elapsed = result != null ? result.bagValue() / 10 : phase.equals("ACTIVE")
                ? Math.max(0, (now - startsAt) / 1_000_000_000.0) : 0;
        List<PlayerView> views = players.values().stream().map(p -> view(p, elapsed * 10)).toList();
        double best = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        List<String> winners = phase.equals("FINISHED") ? scores.entrySet().stream()
                .filter(e -> Double.compare(e.getValue(), best) == 0).map(Map.Entry::getKey).toList() : List.of();
        return new Snapshot(matchId, roundId, revision, phase, roundNumber, 10, elapsed,
                phase.equals("COUNTDOWN") ? Math.max(0, (startsAt - now) / 1_000_000_000.0) : 0,
                elapsed * 10, views, result, winners);
    }
    private void emit(String type) {
        revision++;
        Snapshot state = snapshot();
        events.add(new Message(type, matchId, roundId, revision, state));
    }
    public synchronized Message snapshotMessage() {
        return new Message("STATE_SNAPSHOT", matchId, roundId, revision, snapshot());
    }
}
