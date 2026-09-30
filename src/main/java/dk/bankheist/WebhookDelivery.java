package dk.bankheist;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static dk.bankheist.Protocol.*;

/** In-memory outbox. A dedicated worker keeps slow receivers away from game timers. */
public final class WebhookDelivery implements AutoCloseable {
    public record Delivery(RoundResult event, int attempts, String status, String lastError) {}
    private final Map<String, Delivery> deliveries = new LinkedHashMap<>();
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final URI receiver;
    private final long retryMillis;
    private final int maxAttempts;
    public WebhookDelivery(URI receiver) { this(receiver, 1000, 8); }
    WebhookDelivery(URI receiver, long retryMillis, int maxAttempts) {
        this.receiver = receiver; this.retryMillis = retryMillis; this.maxAttempts = maxAttempts;
    }
    public synchronized void enqueue(RoundResult event) {
        if (deliveries.containsKey(event.eventId())) return;
        deliveries.put(event.eventId(), new Delivery(event, 0, "PENDING", null));
        worker.execute(() -> deliver(event.eventId()));
    }
    public synchronized List<Delivery> list() { return List.copyOf(deliveries.values()); }
    public synchronized boolean retry(String id) {
        Delivery old = deliveries.get(id);
        if (old == null) return false;
        if (old.status().equals("PENDING")) return true;
        deliveries.put(id, new Delivery(old.event(), 0, "PENDING", null));
        worker.execute(() -> deliver(id)); return true;
    }
    private void deliver(String id) {
        Delivery old;
        synchronized (this) { old = deliveries.get(id); }
        String error = null;
        try {
            HttpRequest request = HttpRequest.newBuilder(receiver).timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(old.event()))).build();
            int code = client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code < 200 || code >= 300) error = "HTTP " + code;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        int attempts = old.attempts() + 1;
        String status = error == null ? "DELIVERED" : attempts >= maxAttempts ? "FAILED" : "PENDING";
        synchronized (this) { deliveries.put(id, new Delivery(old.event(), attempts, status, error)); }
        if (error != null) System.err.println("Webhook " + id + " attempt " + attempts + ": " + error);
        if (status.equals("PENDING") && !worker.isShutdown())
            worker.schedule(() -> deliver(id), Math.min(30_000, retryMillis * (1L << Math.min(attempts - 1, 20))), TimeUnit.MILLISECONDS);
    }
    @Override public void close() { worker.shutdownNow(); }
}
