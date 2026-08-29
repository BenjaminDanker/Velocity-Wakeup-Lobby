package com.silver.wakeup.admission;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Polls the primary-ram memory admission controller.
 *
 * <p>Any error fails closed. A fresh player is only released from the lobby
 * when the controller explicitly reports OPEN.</p>
 */
public final class AdmissionClient {
    private final HttpClient client;
    private final URI statusUri;

    private volatile boolean open;

    public AdmissionClient(String statusUrl) {
        this.statusUri = URI.create(statusUrl);
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        this.open = false;
    }

    public boolean isOpen() {
        return open;
    }

    /** Refresh cached admission state. Safe to run from a Velocity scheduler task. */
    public void refresh() {
        HttpRequest request = HttpRequest.newBuilder(statusUri)
                .timeout(Duration.ofSeconds(1))
                .GET()
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                open = false;
                return;
            }

            String body = response.body();
            open = body != null
                    && (body.contains("\"admission\": \"OPEN\"")
                    || body.contains("\"admission\":\"OPEN\""));
        } catch (Exception ignored) {
            open = false;
        }
    }
}
