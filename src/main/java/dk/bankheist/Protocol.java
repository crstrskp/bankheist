package dk.bankheist;

import java.util.List;

/** Immutable wire messages: credentials and the hidden deadline never enter a snapshot. */
public final class Protocol {
    private Protocol() {}
    public record JoinRequest(String name) {}
    public record Credentials(String playerId, String reconnectToken) {}
    public record PlayerView(String playerId, String name, double score, boolean participant,
                             String roundStatus, double roundEarnings, double escapePayout) {}
    public record Earning(String playerId, double earnings, String status) {}
    public record EscapeResult(String matchId, String roundId, String playerId, String outcome, double earnings) {}
    public record RoundResult(String eventId, String matchId, String roundId, String outcome,
                              double bagValue, String endedAt, List<Earning> earnings) {
        public RoundResult { earnings = List.copyOf(earnings); }
    }
    public record Snapshot(String matchId, String roundId, long revision, String phase, int roundNumber,
                           int totalRounds, double elapsedSeconds, double countdownSeconds, double bagValue,
                           List<PlayerView> players, RoundResult result, List<String> winnerIds) {}
    public record Message(String type, String matchId, String roundId, long revision, Snapshot state) {}
}
