package dk.bankheist;

import io.javalin.http.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static dk.bankheist.Protocol.*;
import static org.junit.jupiter.api.Assertions.*;

class GameTest {
    private final AtomicLong now = new AtomicLong();
    private final Game game = new Game(now::get, () -> 25_000_000_000L, 3_000_000_000L, true);
    private final Credentials alice = game.join("Alice"), bob = game.join("Bob");
    private Snapshot start() {
        Snapshot round = game.startRound(game.createMatch().matchId());
        now.addAndGet(3_000_000_000L); game.tick(); return round;
    }
    @Test void individualEscapesBankPersonalBagsAndOnlyLastEscapeEndsRound() {
        Snapshot round = start(); now.addAndGet(10_000_000_000L);
        assertEquals(100, game.escape(round.roundId(), alice.reconnectToken()).earnings());
        assertEquals("ACTIVE", game.snapshot().phase());
        assertNull(game.snapshot().result());
        assertEquals("ESCAPED", game.identity(alice.reconnectToken()).roundStatus());
        assertEquals("INSIDE", game.identity(bob.reconnectToken()).roundStatus());
        assertEquals(100, game.identity(alice.reconnectToken()).score());
        assertEquals(0, game.events().stream().filter(e -> e.type().equals("ROUND_ENDED")).count());
        now.addAndGet(10_000_000_000L);
        assertEquals(200, game.identity(bob.reconnectToken()).escapePayout());
        assertEquals(100, game.identity(alice.reconnectToken()).roundEarnings());
        assertEquals(200, game.escape(round.roundId(), bob.reconnectToken()).earnings());
        assertEquals("ALL_ESCAPED", game.snapshot().result().outcome());
        assertEquals(100, game.identity(alice.reconnectToken()).score());
        assertEquals(200, game.identity(bob.reconnectToken()).score());
        assertEquals(1, game.events().stream().filter(e -> e.type().equals("ROUND_ENDED")).count());
    }
    @Test void bagGrowthDoesNotDependOnCrewSize() {
        game.join("Charlie"); game.join("Dana");
        Snapshot round = start(); now.addAndGet(10_000_000_000L);
        assertEquals(100, game.identity(alice.reconnectToken()).escapePayout());
        assertEquals(100, game.escape(round.roundId(), alice.reconnectToken()).earnings());
        now.addAndGet(5_000_000_000L);
        assertEquals(150, game.identity(bob.reconnectToken()).escapePayout());
        assertEquals(100, game.identity(alice.reconnectToken()).roundEarnings());
    }
    @Test void alarmCatchesOnlyRemainingPlayersAndPreservesBankedGold() {
        Snapshot round = start(); now.addAndGet(10_000_000_000L);
        game.escape(round.roundId(), alice.reconnectToken());
        now.addAndGet(15_000_000_000L);
        assertThrows(ConflictResponse.class, () -> game.escape(round.roundId(), bob.reconnectToken()));
        assertEquals("ALARM", game.snapshot().result().outcome());
        assertEquals("ESCAPED", game.identity(alice.reconnectToken()).roundStatus());
        assertEquals(100, game.identity(alice.reconnectToken()).score());
        assertEquals("CAUGHT", game.identity(bob.reconnectToken()).roundStatus());
        assertEquals(250, game.snapshot().result().bagValue());
        assertEquals(0, game.identity(bob.reconnectToken()).score());
    }
    @Test void duplicateEscapeNeverCreditsTwiceOrEndsRound() {
        Snapshot round = start(); now.addAndGet(10_000_000_000L);
        game.escape(round.roundId(), alice.reconnectToken());
        assertThrows(ConflictResponse.class, () -> game.escape(round.roundId(), alice.reconnectToken()));
        assertEquals(100, game.identity(alice.reconnectToken()).score());
        assertEquals("ACTIVE", game.snapshot().phase());
    }
    @Test void alarmWinsExactlyAtDeadlineEvenWithoutTimerCallback() {
        Snapshot round = start(); now.addAndGet(25_000_000_000L);
        assertThrows(ConflictResponse.class, () -> game.escape(round.roundId(), alice.reconnectToken()));
        assertTrue(game.snapshot().result().earnings().stream().allMatch(e -> e.earnings() == 0 && e.status().equals("CAUGHT")));
    }
    @Test void escapeOneNanosecondBeforeDeadlineIsSafe() {
        Snapshot round = start(); now.addAndGet(24_999_999_999L);
        double banked = game.escape(round.roundId(), alice.reconnectToken()).earnings();
        now.incrementAndGet(); game.tick();
        assertEquals("ALARM", game.snapshot().result().outcome());
        assertEquals(banked, game.identity(alice.reconnectToken()).score());
    }
    @Test void nextRoundResetsPersonalStateButPreservesScoresAndRejectsOldActions() {
        Snapshot first = start(); now.addAndGet(10_000_000_000L);
        game.escape(first.roundId(), alice.reconnectToken()); game.escape(first.roundId(), bob.reconnectToken());
        game.startRound(first.matchId()); now.addAndGet(3_000_000_000L); game.tick();
        assertEquals("INSIDE", game.identity(alice.reconnectToken()).roundStatus());
        assertEquals(0, game.identity(alice.reconnectToken()).roundEarnings());
        assertEquals(100, game.identity(alice.reconnectToken()).score());
        assertThrows(ConflictResponse.class, () -> game.escape(first.roundId(), bob.reconnectToken()));
        assertEquals("ACTIVE", game.snapshot().phase());
    }
    @Test void countdownAndLateJoinersCannotEscapeOrChangeBags() {
        Snapshot round = game.startRound(game.createMatch().matchId());
        assertThrows(ConflictResponse.class, () -> game.escape(round.roundId(), alice.reconnectToken()));
        Credentials late = game.join("Late"); now.addAndGet(13_000_000_000L); game.tick();
        assertThrows(ConflictResponse.class, () -> game.escape(round.roundId(), late.reconnectToken()));
        assertEquals(100, game.escape(round.roundId(), alice.reconnectToken()).earnings());
        assertFalse(game.identity(late.reconnectToken()).participant());
        assertThrows(UnauthorizedResponse.class, () -> game.escape(round.roundId(), "invented"));
    }
    @Test void simultaneousDistinctEscapesBothSucceedAndCloseExactlyOnce() throws Exception {
        race(false, 2);
    }
    @Test void simultaneousDuplicateEscapesCreditExactlyOnce() throws Exception {
        race(true, 1);
    }
    private void race(boolean samePlayer, int expectedSuccesses) throws Exception {
        Snapshot round = start(); now.addAndGet(10_000_000_000L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (Credentials player : List.of(alice, samePlayer ? alice : bob)) results.add(pool.submit(() -> {
                ready.countDown(); go.await();
                try { game.escape(round.roundId(), player.reconnectToken()); return true; }
                catch (ConflictResponse e) { return false; }
            }));
            assertTrue(ready.await(2, TimeUnit.SECONDS)); go.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) if (result.get(2, TimeUnit.SECONDS)) successes++;
            assertEquals(expectedSuccesses, successes);
            assertEquals(samePlayer ? 0 : 1, game.events().stream().filter(e -> e.type().equals("ROUND_ENDED")).count());
            assertEquals(100 * expectedSuccesses, game.snapshot().players().stream().mapToDouble(PlayerView::score).sum());
        } finally { pool.shutdownNow(); }
    }
    @Test void tenAlarmRoundsFinishInATieAndNextMatchResetsScores() {
        Snapshot match = game.createMatch();
        for (int i = 0; i < 10; i++) {
            game.startRound(match.matchId()); now.addAndGet(28_000_000_000L); game.tick();
        }
        assertEquals("FINISHED", game.snapshot().phase());
        assertEquals(2, game.snapshot().winnerIds().size());
        assertThrows(ConflictResponse.class, () -> game.startRound(match.matchId()));
        assertEquals("READY", game.createMatch().phase());
        assertEquals(0, game.snapshot().roundNumber());
    }
    @Test void alarmDisabledWaitsForEveryPlayerAndImmediateEscapeBanksZero() {
        Game calm = new Game(now::get, () -> 5_000_000_000L, 0, false);
        Credentials p = calm.join("A"), q = calm.join("B");
        Snapshot round = calm.startRound(calm.createMatch().matchId());
        assertEquals(0, calm.escape(round.roundId(), p.reconnectToken()).earnings());
        now.set(100_000_000_000L); calm.tick();
        assertEquals("ACTIVE", calm.snapshot().phase());
        calm.escape(round.roundId(), q.reconnectToken());
        assertEquals("ALL_ESCAPED", calm.snapshot().result().outcome());
    }
    @Test void enforcesLobbyBoundsAndNameValidation() {
        Game lobby = new Game(false);
        assertThrows(ConflictResponse.class, lobby::createMatch);
        assertThrows(BadRequestResponse.class, () -> lobby.join(" "));
        for (int i = 0; i < 8; i++) lobby.join("Player " + i);
        assertThrows(ConflictResponse.class, () -> lobby.join("Ninth"));
    }
}
