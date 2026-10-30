package com.silver.wakeup.admission;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Talks to the capacity service on the machine that runs the servers (Host-Ops host_capacity.py, port 25580).
 *
 * <p>The join gate fails closed: a fresh player is only released from the lobby while the service reports OPEN. A short outage
 * (a restart) keeps the last answer for {@link #GRACE_MS}. A server start asks for a reservation first; when the service can't be
 * reached at all the start goes ahead, as it did before the service existed.</p>
 */
public final class AdmissionClient {
    /** A refused start and why; {@code null} from {@link #reserveStart} means the service did not answer. */
    public record Permit(boolean granted, String reason) {
    }

    static final int PORT = 25580;
    static final long GRACE_MS = 60_000;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
    private volatile URI base;

    private volatile boolean open;
    private volatile long lastGoodMs;
    private volatile Map<String, Boolean> accepting = Map.of();

    public AdmissionClient(String host) {
        configure(host);
    }

    /** The host of the machine that runs the servers (the on-demand ssh target's host). */
    public void configure(String host) {
        this.base = URI.create("http://" + host + ":" + PORT);
    }

    public boolean isOpen() {
        return open;
    }

    /** False while the server reports ticks slower than the service's limit (it takes no new players then). Unknown means yes. */
    public boolean isAccepting(String server) {
        return accepting.getOrDefault(server, Boolean.TRUE);
    }

    /** Refresh cached admission state. Safe to run from a Velocity scheduler task. */
    public void refresh() {
        try {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/status")).timeout(Duration.ofSeconds(1)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                failed();
                return;
            }
            apply(JsonParser.parseString(response.body()).getAsJsonObject(), System.currentTimeMillis());
        } catch (Exception ignored) {
            failed();
        }
    }

    void apply(JsonObject status, long nowMs) {
        open = status.has("admission") && "OPEN".equals(status.get("admission").getAsString());
        Map<String, Boolean> next = new ConcurrentHashMap<>();
        JsonElement servers = status.get("servers");
        if (servers != null && servers.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : servers.getAsJsonObject().entrySet()) {
                JsonElement value = entry.getValue().isJsonObject() ? entry.getValue().getAsJsonObject().get("accepting") : null;
                next.put(entry.getKey(), value == null || value.isJsonNull() || value.getAsBoolean());
            }
        }
        accepting = next;
        lastGoodMs = nowMs;
    }

    void failed() {
        failed(System.currentTimeMillis());
    }

    void failed(long nowMs) {
        if (nowMs - lastGoodMs > GRACE_MS) {
            open = false;
            accepting = Map.of();
        }
    }

    /** Ask for room to start {@code server}. Blocking (call it off the proxy's threads); null when the service did not answer. */
    public Permit reserveStart(String server) {
        JsonObject body = new JsonObject();
        body.addProperty("kind", "mc_start");
        body.addProperty("name", server);
        try {
            HttpRequest request = HttpRequest.newBuilder(base.resolve("/reserve"))
                    .timeout(Duration.ofSeconds(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            JsonObject reply = JsonParser.parseString(response.body()).getAsJsonObject();
            boolean granted = reply.has("granted") && reply.get("granted").getAsBoolean();
            String reason = reply.has("reason") && !reply.get("reason").isJsonNull() ? reply.get("reason").getAsString() : "";
            return new Permit(granted, reason);
        } catch (Exception ignored) {
            return null;
        }
    }
}
